package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.SubmitExamRequest;
import graduation_project_be.application.usecases.response.SubmitExamResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class SubmitExamUsecase {

    private final ExamRepository examRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamResultRepository examResultRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;

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

        // 3. Load all questions for this exam (indexed by ID)
        List<ExamQuestion> allQuestions = examQuestionRepository.findByExamId(examId);
        Map<Long, ExamQuestion> questionMap = allQuestions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));

        String schemaName = String.format("exam_%d_student_%d", examId, studentId);

        // 4. Reset schema — drop all existing objects so student SQL runs cleanly
        log.info("Resetting schema [{}] before grading", schemaName);
        examSchemaService.resetSchema(schemaName);

        // 5. Grade each answer sequentially (order matters for DDL → DML → SELECT)
        List<SubmitExamResponse.QuestionResult> results = new ArrayList<>();
        BigDecimal totalScore = BigDecimal.ZERO;
        BigDecimal maxScore = BigDecimal.ZERO;
        int correctCount = 0;

        for (SubmitExamRequest.AnswerItem answer : request.answers()) {
            ExamQuestion question = questionMap.get(answer.questionId());
            if (question == null) {
                log.warn("Question {} not found in exam {}, skipping", answer.questionId(), examId);
                continue;
            }

            maxScore = maxScore.add(question.getPoints());

            // Grade this answer
            boolean isCorrect = false;
            String errorMessage = null;
            int executionTimeMs = 0;

            long startTime = System.currentTimeMillis();
            try {
                // Execute student's SQL
                examSchemaService.executeSql(schemaName, answer.studentQuery());
                executionTimeMs = (int) (System.currentTimeMillis() - startTime);

                // Grade based on question type
                isCorrect = gradeAnswer(schemaName, question);

            } catch (Exception e) {
                executionTimeMs = (int) (System.currentTimeMillis() - startTime);
                errorMessage = e.getMessage();
                log.warn("Q{} execution failed: {}", answer.questionId(), e.getMessage());
            }

            BigDecimal scoreEarned = isCorrect ? question.getPoints() : BigDecimal.ZERO;
            if (isCorrect)
                correctCount++;
            totalScore = totalScore.add(scoreEarned);

            // Save individual submission to DB
            ExamSubmission submission = ExamSubmission.builder()
                    .examId(examId)
                    .questionId(answer.questionId())
                    .studentId(studentId)
                    .assignedSchemaName(schemaName)
                    .studentQuery(answer.studentQuery())
                    .isCorrect(isCorrect)
                    .scoreEarned(scoreEarned)
                    .errorMessage(errorMessage)
                    .executionTimeMs(executionTimeMs)
                    .status("SUBMITTED")
                    .submittedAt(submittedAt)
                    .build();

            ExamSubmission saved = examSubmissionRepository.save(submission);

            // Add to results
            results.add(new SubmitExamResponse.QuestionResult(
                    saved.getId(),
                    answer.questionId(),
                    question.getOrderIndex(),
                    answer.studentQuery(),
                    isCorrect,
                    scoreEarned,
                    question.getPoints(),
                    errorMessage,
                    executionTimeMs));
        }

        // 6. Save total result to exam_results table
        ExamResult examResult = ExamResult.builder()
                .examId(examId)
                .studentId(studentId)
                .totalScore(totalScore)
                .maxScore(maxScore)
                .totalQuestions(results.size())
                .correctCount(correctCount)
                .submittedAt(submittedAt)
                .build();
        examResultRepository.save(examResult);

        // 7. Cleanup — drop the student's schema after grading is complete
        try {
            examSchemaService.dropSchema(schemaName);
        } catch (Exception e) {
            log.warn("Failed to drop schema [{}] after grading: {}", schemaName, e.getMessage());
        }

        return new SubmitExamResponse(
                examId, studentId, totalScore, maxScore,
                results.size(), correctCount, submittedAt, results);
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
}
