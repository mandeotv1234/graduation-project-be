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
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SpecDataset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private final ExamSpecificationRepository examSpecificationRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final ExamSessionService examSessionService;

    @Transactional
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
        long lateDurationSeconds = validateExamTime(examId, studentId, exam, submittedAt);

        ExamSpecification specification = null;
        if (exam.getSpecificationId() != null) {
            specification = examSpecificationRepository.findById(exam.getSpecificationId()).orElse(null);
            if (specification == null) {
                log.warn("Specification {} not found for exam {}", exam.getSpecificationId(), examId);
            }
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
        Map<Long, String> answerMap = new LinkedHashMap<>();
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

        // Determine the current attempt number (1-based)
        long previousAttempts = examResultRepository.countByExamIdAndStudentId(examId, studentId);

        // Validate maxAttempts — block student if they have exhausted all attempts
        if (exam.getMaxAttempts() != null && exam.getMaxAttempts() > 0) {
            if (previousAttempts >= exam.getMaxAttempts()) {
                throw new BadRequestException(
                        "Bạn đã hết số lần làm bài (" + previousAttempts + "/" + exam.getMaxAttempts() + ").");
            }
        }

        int attemptNumber = (int) previousAttempts + 1;

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
                    if (question.getQuestionType() == QuestionType.SELECT_QUERY) {
                        GradeDecision decision = gradeSelectAcrossDatasets(
                                specification, schemaName, question, studentQuery);
                        isCorrect = decision.isCorrect();
                        errorMessage = decision.errorMessage();
                    } else {
                        // Execute student's SQL for non-SELECT question types
                        examSchemaService.executeSql(schemaName, studentQuery);

                        // Grade based on question type
                        isCorrect = gradeAnswer(schemaName, question);
                        if (!isCorrect) {
                            errorMessage = "Answer did not match expected result";
                        }
                    }
                    executionTimeMs = (int) (System.currentTimeMillis() - startTime);
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
                    .attemptNumber(attemptNumber)
                    .assignedSchemaName(schemaName)
                    .studentQuery(studentQuery != null ? studentQuery : "")
                    .isCorrect(isCorrect)
                    .scoreEarned(scoreEarned)
                    .errorMessage(errorMessage)
                    .executionTimeMs(executionTimeMs)
                    .status("SUBMITTED")
                    .submittedAt(submittedAt)
                    .build();

            // Always INSERT a new record per attempt (not upsert)
            ExamSubmission saved = examSubmissionRepository.save(submission);

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

        // 6. INSERT total result to exam_results table (per attempt)
        ExamResult examResult = ExamResult.builder()
                .examId(examId)
                .studentId(studentId)
                .attemptNumber(attemptNumber)
                .totalScore(totalScore)
                .maxScore(maxScore)
                .totalQuestions(totalQuestions)
                .correctCount(correctCount)
                .lateDurationSeconds((int) lateDurationSeconds)
                .submittedAt(submittedAt)
                .build();
        examResultRepository.save(examResult);

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
                totalQuestions, correctCount, (int) lateDurationSeconds, submittedAt, results);
    }

    // ========== Grading Logic ==========

    private boolean gradeAnswer(String schemaName, ExamQuestion question) {
        QuestionType type = question.getQuestionType();
        switch (type) {
            case CREATE_TABLE:
            case INSERT_DATA:
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

    private GradeDecision gradeSelectAcrossDatasets(
            ExamSpecification specification,
            String schemaName,
            ExamQuestion question,
            String studentQuery) {
        if (question.getCorrectQuery() == null || question.getCorrectQuery().isBlank()) {
            return GradeDecision.fail("Missing correctQuery for SELECT question");
        }
        if (specification == null) {
            return GradeDecision.fail("Exam has no specification for multi-dataset grading");
        }
        if (specification.getDdlScript() == null || specification.getDdlScript().isBlank()) {
            return GradeDecision.fail("Specification has no ddlScript");
        }

        List<SpecDataset> activeDatasets = specification.getDatasets() == null ? List.of()
                : specification.getDatasets().stream()
                        .filter(SpecDataset::isActive)
                        .sorted(Comparator.comparingInt(SpecDataset::getOrderIndex))
                        .toList();

        if (activeDatasets.isEmpty()) {
            return gradeSelectWithSingleDataset(
                    schemaName,
                    specification.getDdlScript(),
                    null,
                    "fallback-no-dataset",
                    question,
                    studentQuery);
        }

        for (SpecDataset dataset : activeDatasets) {
            String datasetLabel = "dataset[" + dataset.getId() + ":" + dataset.getName() + "]";
            GradeDecision decision = gradeSelectWithSingleDataset(
                    schemaName,
                    specification.getDdlScript(),
                    dataset.getDataScript(),
                    datasetLabel,
                    question,
                    studentQuery);
            if (!decision.isCorrect()) {
                return decision;
            }
        }

        return GradeDecision.pass();
    }

    private GradeDecision gradeSelectWithSingleDataset(
            String schemaName,
            String ddlScript,
            String datasetScript,
            String datasetLabel,
            ExamQuestion question,
            String studentQuery) {
        try {
            examSchemaService.resetSchema(schemaName);
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, datasetScript);

            List<Map<String, Object>> actual = examSchemaService.executeSql(schemaName, studentQuery);
            List<Map<String, Object>> expected = examSchemaService.executeSql(schemaName, question.getCorrectQuery());

            if (!compareResultSetsStrict(actual, expected)) {
                return GradeDecision.fail("SELECT result mismatch on " + datasetLabel);
            }
            return GradeDecision.pass();
        } catch (Exception e) {
            return GradeDecision.fail("Failed on " + datasetLabel + ": " + e.getMessage());
        }
    }

    private record GradeDecision(boolean isCorrect, String errorMessage) {
        static GradeDecision pass() {
            return new GradeDecision(true, null);
        }

        static GradeDecision fail(String message) {
            return new GradeDecision(false, message);
        }
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
    private long validateExamTime(Long examId, Long studentId, Exam exam, LocalDateTime submittedAt) {
        Optional<LocalDateTime> startTimeOpt = examSessionService.getExamStartTime(examId, studentId);

        if (startTimeOpt.isPresent()) {
            LocalDateTime examStartedAt = startTimeOpt.get();
            LocalDateTime examDeadline = examStartedAt.plusMinutes(exam.getDurationMinutes());

            // If exam has a hard end time, use the earlier of the two
            if (exam.getEndTime() != null && exam.getEndTime().isBefore(examDeadline)) {
                examDeadline = exam.getEndTime();
            }

            long secondsOverdue = Duration.between(examDeadline, submittedAt).getSeconds();

            // Determine allowable late window from exam settings
            boolean allowOvertime = exam.getSettings() != null
                    && Boolean.TRUE.equals(exam.getSettings().getAllowOvertime());
            int lateThresholdMinutes = exam.getLateThreshold() != null ? exam.getLateThreshold() : 0;

            if (secondsOverdue > SUBMIT_GRACE_SECONDS) {
                if (allowOvertime && lateThresholdMinutes > 0) {
                    // Check if within the late submission window
                    long lateThresholdSeconds = (long) lateThresholdMinutes * 60;
                    if (secondsOverdue <= lateThresholdSeconds) {
                        log.info("Late submission accepted within lateThreshold: exam={}, student={}, overdue={}s, threshold={}min",
                                examId, studentId, secondsOverdue, lateThresholdMinutes);
                        // Allow — within the late submission window
                        return secondsOverdue;
                    }
                    // Beyond the late threshold
                    log.warn("Late submission rejected: exam={}, student={}, overdue={}s exceeds lateThreshold={}min",
                            examId, studentId, secondsOverdue, lateThresholdMinutes);
                    throw new BadRequestException(
                            "Thời gian nộp bài trễ đã vượt quá ngưỡng cho phép (" + lateThresholdMinutes + " phút).");
                }

                // allowOvertime is false — strict deadline
                log.warn("Late submission rejected: exam={}, student={}, overdue={}s (grace={}s)",
                        examId, studentId, secondsOverdue, SUBMIT_GRACE_SECONDS);
                throw new BadRequestException(
                        "Exam time has expired. Submission was " + secondsOverdue + " seconds late.");
            }

            if (secondsOverdue > 0) {
                log.info("Late submission accepted within grace period: exam={}, student={}, overdue={}s",
                        examId, studentId, secondsOverdue);
                return secondsOverdue;
            }
        } else {
            log.warn("No backend start time found for exam={}, student={}. Allowing submission.",
                    examId, studentId);
        }

        return 0L;
    }
}
