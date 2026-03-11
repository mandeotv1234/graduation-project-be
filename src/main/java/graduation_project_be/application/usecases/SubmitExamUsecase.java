package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.SubmitExamRequest;
import graduation_project_be.application.usecases.response.SubmitExamResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.TemplateDataset;
import graduation_project_be.domain.models.QuestionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class SubmitExamUsecase {

    /** Grace period (seconds) to account for network latency */
    private static final long SUBMIT_GRACE_SECONDS = 30;

    private final ExamRepository examRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamResultRepository examResultRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final ExamSessionService examSessionService;
    private final TemplateDatasetRepository templateDatasetRepository;

    public SubmitExamResponse execute(SubmitExamRequest request) {
        Long studentId = currentUserService.getCurrentUserId();
        Long examId = request.examId();
        LocalDateTime submittedAt = LocalDateTime.now();

        // 1. Validate exam
        Exam exam = examRepository.findByIdAndIsPublished(examId, true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        // 2. Validate enrollment
        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), studentId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        // 2b. Backend time validation — prevent DevTools time manipulation
        validateExamTime(examId, studentId, exam, submittedAt);

        // 2c. Load reference datasets for multi-dataset grading (SELECT_QUERY only)
        List<TemplateDataset> refDatasets = List.of();
        if (exam.getTemplateId() != null) {
            refDatasets = templateDatasetRepository.findByTemplateId(exam.getTemplateId());
        }

        // 3. Load all questions for this exam (indexed by ID)
        List<ExamQuestion> allQuestions = examQuestionRepository.findByExamId(examId);
        Map<Long, ExamQuestion> questionMap = allQuestions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));

        // Compute maxScore from ALL exam questions (not from submitted answers)
        BigDecimal maxScore = allQuestions.stream()
                .map(ExamQuestion::getPoints)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalQuestions = allQuestions.size();

        // 3b. Validate submitted answers: no duplicates, all questionIds must belong to
        // this exam
        Map<Long, String> answerMap = new java.util.LinkedHashMap<>();
        for (SubmitExamRequest.AnswerItem answer : request.answers()) {
            if (!questionMap.containsKey(answer.questionId())) {
                throw new IllegalArgumentException("Question " + answer.questionId() + " does not belong to this exam");
            }
            if (answerMap.containsKey(answer.questionId())) {
                throw new IllegalArgumentException("Duplicate answer for question " + answer.questionId());
            }
            answerMap.put(answer.questionId(), answer.studentQuery());
        }

        String schemaName = String.format("exam_%d_student_%d", examId, studentId);

        // 4. Reset schema — drop all existing objects so student SQL runs cleanly
        log.info("Resetting schema [{}] before grading", schemaName);
        examSchemaService.resetSchema(schemaName);

        // 5. Grade ALL questions sequentially (order by orderIndex, DDL → DML → SELECT)
        // Missing answers are treated as incorrect (0 score)
        List<SubmitExamResponse.QuestionResult> results = new ArrayList<>();
        BigDecimal totalScore = BigDecimal.ZERO;
        int correctCount = 0;

        // Sort questions by orderIndex to ensure correct execution order
        List<ExamQuestion> sortedQuestions = allQuestions.stream()
                .sorted((a, b) -> Integer.compare(a.getOrderIndex(), b.getOrderIndex()))
                .toList();

        for (ExamQuestion question : sortedQuestions) {
            String studentQuery = answerMap.get(question.getId());

            boolean isCorrect = false;
            String errorMessage = null;
            int executionTimeMs = 0;

            if (studentQuery == null || studentQuery.isBlank()) {
                // Student did not answer this question
                errorMessage = "No answer submitted";
            } else {
                long startTime = System.currentTimeMillis();
                try {
                    // SELECT_QUERY with multi-dataset: grade directly on reference schemas
                    if (question.getQuestionType() == QuestionType.SELECT_QUERY
                            && refDatasets != null && !refDatasets.isEmpty()) {
                        isCorrect = gradeSelectQueryOnRefSchemas(question, studentQuery, refDatasets);
                        executionTimeMs = (int) (System.currentTimeMillis() - startTime);
                    } else {
                        // Execute student's SQL on student schema
                        examSchemaService.executeSql(schemaName, studentQuery);
                        executionTimeMs = (int) (System.currentTimeMillis() - startTime);
                        isCorrect = gradeAnswer(schemaName, question);
                    }
                } catch (Exception e) {
                    executionTimeMs = (int) (System.currentTimeMillis() - startTime);
                    errorMessage = e.getMessage();
                    log.warn("Q{} execution failed: {}", question.getId(), e.getMessage());
                }
            }

            BigDecimal scoreEarned = isCorrect ? question.getPoints() : BigDecimal.ZERO;
            if (isCorrect)
                correctCount++;
            totalScore = totalScore.add(scoreEarned);

            // Save individual submission to DB (upsert — see Issue 6)
            ExamSubmission submission = ExamSubmission.builder()
                    .examId(examId)
                    .questionId(question.getId())
                    .studentId(studentId)
                    .assignedSchemaName(schemaName)
                    .studentQuery(studentQuery != null ? studentQuery : "")
                    .isCorrect(isCorrect)
                    .scoreEarned(scoreEarned)
                    .errorMessage(errorMessage)
                    .executionTimeMs(executionTimeMs)
                    .status("SUBMITTED")
                    .submittedAt(submittedAt)
                    .build();

            // Upsert: update existing submission if student re-submits
            ExamSubmission saved = upsertSubmission(submission);

            // Add to results
            results.add(new SubmitExamResponse.QuestionResult(
                    saved.getId(),
                    question.getId(),
                    question.getOrderIndex(),
                    studentQuery != null ? studentQuery : "",
                    isCorrect,
                    scoreEarned,
                    question.getPoints(),
                    errorMessage,
                    executionTimeMs));
        }

        // 6. Upsert total result to exam_results table
        ExamResult examResult = ExamResult.builder()
                .examId(examId)
                .studentId(studentId)
                .totalScore(totalScore)
                .maxScore(maxScore)
                .totalQuestions(totalQuestions)
                .correctCount(correctCount)
                .submittedAt(submittedAt)
                .build();
        upsertExamResult(examResult);

        // 7. Cleanup — drop the student's schema after grading is complete
        try {
            examSchemaService.dropSchema(schemaName);
        } catch (Exception e) {
            log.warn("Failed to drop schema [{}] after grading: {}", schemaName, e.getMessage());
        }

        // 8. End session — release Redis session lock
        try {
            examSessionService.endSession(examId, studentId);
        } catch (Exception e) {
            log.warn("Failed to end session for exam={}, student={}: {}", examId, studentId, e.getMessage());
        }

        return new SubmitExamResponse(
                examId, studentId, totalScore, maxScore,
                totalQuestions, correctCount, submittedAt, results);
    }

    // ========== Upsert helpers ==========

    private ExamSubmission upsertSubmission(ExamSubmission submission) {
        var existing = examSubmissionRepository.findByExamIdAndQuestionIdAndStudentId(
                submission.getExamId(), submission.getQuestionId(), submission.getStudentId());
        if (existing.isPresent()) {
            // Update existing submission
            ExamSubmission toUpdate = existing.get();
            toUpdate.setStudentQuery(submission.getStudentQuery());
            toUpdate.setIsCorrect(submission.getIsCorrect());
            toUpdate.setScoreEarned(submission.getScoreEarned());
            toUpdate.setErrorMessage(submission.getErrorMessage());
            toUpdate.setExecutionTimeMs(submission.getExecutionTimeMs());
            toUpdate.setStatus(submission.getStatus());
            toUpdate.setSubmittedAt(submission.getSubmittedAt());
            return examSubmissionRepository.save(toUpdate);
        }
        return examSubmissionRepository.save(submission);
    }

    private void upsertExamResult(ExamResult examResult) {
        var existing = examResultRepository.findByExamIdAndStudentId(
                examResult.getExamId(), examResult.getStudentId());
        if (existing.isPresent()) {
            ExamResult toUpdate = existing.get();
            toUpdate.setTotalScore(examResult.getTotalScore());
            toUpdate.setMaxScore(examResult.getMaxScore());
            toUpdate.setTotalQuestions(examResult.getTotalQuestions());
            toUpdate.setCorrectCount(examResult.getCorrectCount());
            toUpdate.setSubmittedAt(examResult.getSubmittedAt());
            examResultRepository.save(toUpdate);
        } else {
            examResultRepository.save(examResult);
        }
    }

    // ========== Grading Logic ==========

    private boolean gradeAnswer(String schemaName, ExamQuestion question) {
        QuestionType type = question.getQuestionType();
        switch (type) {
            case CREATE_TABLE:
            case INSERT_DATA:
            case SELECT_QUERY:
                return gradeByStrictComparison(schemaName, question);
            case TRIGGER:
            case FUNCTION:
            case STORED_PROCEDURE:
                return gradeByVerifyScript(schemaName, question);
            default:
                log.warn("Unknown question type: {}", type);
                return false;
        }
    }

    /**
     * Multi-dataset grading for SELECT_QUERY.
     * Runs studentQuery and correctQuery on each reference schema.
     * All datasets must match for the answer to be correct.
     */
    private boolean gradeSelectQueryOnRefSchemas(ExamQuestion question, String studentQuery,
            List<TemplateDataset> datasets) {
        for (TemplateDataset ds : datasets) {
            try {
                List<Map<String, Object>> actual = examSchemaService.executeSql(
                        ds.getSchemaName(), studentQuery);
                List<Map<String, Object>> expected = examSchemaService.executeSql(
                        ds.getSchemaName(), question.getCorrectQuery());

                if (!compareResultSetsStrict(actual, expected)) {
                    log.info("SELECT_QUERY Q{} failed on dataset schema [{}]",
                            question.getId(), ds.getSchemaName());
                    return false;
                }
            } catch (Exception e) {
                log.warn("SELECT_QUERY Q{} error on dataset schema [{}]: {}",
                        question.getId(), ds.getSchemaName(), e.getMessage());
                return false;
            }
        }
        log.info("SELECT_QUERY Q{} passed all {} dataset(s)",
                question.getId(), datasets.size());
        return true;
    }

    /**
     * Strict grading for CREATE_TABLE / INSERT_DATA / SELECT_QUERY.
     * correctQuery: runs on student's schema → actual
     * verify_script: hardcoded expected data → expected
     * Compare 100% match.
     */
    private boolean gradeByStrictComparison(String schemaName, ExamQuestion question) {
        try {
            List<Map<String, Object>> actual = examSchemaService.executeSql(
                    schemaName, question.getCorrectQuery());

            String verifyScript = question.getVerifyScript();
            if (verifyScript == null || verifyScript.isBlank()) {
                log.warn("No verify_script for Q{}, cannot do strict comparison", question.getId());
                return actual != null && !actual.isEmpty();
            }

            List<Map<String, Object>> expected = examSchemaService.executeSql(
                    schemaName, verifyScript);

            return compareResultSetsStrict(actual, expected);
        } catch (Exception e) {
            log.warn("Strict grading failed for Q{}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Grading for TRIGGER / FUNCTION / STORED_PROCEDURE.
     * verify_script: behavioral test → actual
     * correctQuery: expected output → expected
     * Compare results.
     */
    private boolean gradeByVerifyScript(String schemaName, ExamQuestion question) {
        String verifyScript = question.getVerifyScript();
        if (verifyScript == null || verifyScript.isBlank()) {
            log.warn("No verify_script for Q{}", question.getId());
            return false;
        }

        try {
            // Replace {SCHEMA} placeholder with actual schema name
            // (MSSQL requires schema-qualified names for scalar functions)
            String resolvedScript = verifyScript.replace("{SCHEMA}", schemaName);

            List<Map<String, Object>> actual = examSchemaService.executeSql(
                    schemaName, resolvedScript);
            List<Map<String, Object>> expected = examSchemaService.executeSql(
                    schemaName, question.getCorrectQuery());

            return compareResultSetsStrict(actual, expected);
        } catch (Exception e) {
            log.warn("Verify script failed for Q{}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    private boolean compareResultSetsStrict(List<Map<String, Object>> actual,
            List<Map<String, Object>> expected) {
        if (actual == null || expected == null)
            return false;
        if (actual.size() != expected.size())
            return false;

        for (int i = 0; i < actual.size(); i++) {
            Map<String, Object> actualRow = actual.get(i);
            Map<String, Object> expectedRow = expected.get(i);

            if (actualRow.size() != expectedRow.size())
                return false;

            for (Map.Entry<String, Object> entry : expectedRow.entrySet()) {
                String colName = entry.getKey();
                if (!actualRow.containsKey(colName))
                    return false;

                String actualVal = normalizeValue(actualRow.get(colName));
                String expectedVal = normalizeValue(entry.getValue());

                if (!actualVal.equalsIgnoreCase(expectedVal))
                    return false;
            }
        }
        return true;
    }

    private String normalizeValue(Object value) {
        if (value == null)
            return "null";
        String str = value.toString().trim();
        if (str.matches("-?\\d+\\.\\d+")) {
            str = str.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return str;
    }

    // ========== Backend time validation ==========

    /**
     * Validates that the student's exam has not expired based on
     * the backend-managed start time stored in Redis.
     * This prevents DevTools time manipulation attacks.
     */
    private void validateExamTime(Long examId, Long studentId, Exam exam, LocalDateTime submittedAt) {
        Optional<LocalDateTime> startTimeOpt = examSessionService.getExamStartTime(examId, studentId);

        if (startTimeOpt.isPresent()) {
            LocalDateTime examStartedAt = startTimeOpt.get();
            LocalDateTime examDeadline = examStartedAt.plusMinutes(exam.getDurationMinutes());

            // If exam has a hard end time, use the earlier of the two
            if (exam.getEndTime() != null && exam.getEndTime().isBefore(examDeadline)) {
                examDeadline = exam.getEndTime();
            }

            long secondsOverdue = Duration.between(examDeadline, submittedAt).getSeconds();
            if (secondsOverdue > SUBMIT_GRACE_SECONDS) {
                log.warn("Late submission rejected: exam={}, student={}, overdue={}s (grace={}s)",
                        examId, studentId, secondsOverdue, SUBMIT_GRACE_SECONDS);
                throw new BadRequestException(
                        "Exam time has expired. Submission was " + secondsOverdue + " seconds late.");
            }

            if (secondsOverdue > 0) {
                log.info("Late submission accepted within grace period: exam={}, student={}, overdue={}s",
                        examId, studentId, secondsOverdue);
            }
        } else {
            log.warn("No backend start time found for exam={}, student={}. Allowing submission.",
                    examId, studentId);
        }
    }
}
