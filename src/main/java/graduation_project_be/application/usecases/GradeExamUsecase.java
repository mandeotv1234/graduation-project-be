package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.GradingNotificationService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import graduation_project_be.domain.models.TestCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Background grading usecase — extracted from the old synchronous SubmitExamUsecase.
 * This usecase is invoked by the GradingWorker (not by a controller).
 * It grades all submissions for a specific exam + student + attempt,
 * then notifies the student via WebSocket.
 */
@Slf4j
@RequiredArgsConstructor
public class GradeExamUsecase {

    private final ExamRepository examRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamSchemaService examSchemaService;
    private final ExamSessionService examSessionService;
    private final GradingNotificationService gradingNotificationService;
    private final UserRepository userRepository;
    private final TestCaseRepository testCaseRepository;

    @Transactional
    public void execute(Long examId, Long studentId, int attemptNumber) {
        log.info("Starting grading: exam={}, student={}, attempt={}", examId, studentId, attemptNumber);

        // 1. Load exam
        Exam exam = examRepository.findByIdAndIsPublished(examId, true)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // 2. Update result status to GRADING
        ExamResult existingResult = examResultRepository
                .findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber)
                .orElseThrow(() -> new ResourceNotFoundException("ExamResult", "examId,studentId,attempt",
                        examId + "," + studentId + "," + attemptNumber));
        existingResult.setStatus(GradingStatus.GRADING);
        examResultRepository.save(existingResult);

        try {
            // 3. Load specification
            ExamSpecification specification = null;
            if (exam.getSpecificationId() != null) {
                specification = examSpecificationRepository.findById(exam.getSpecificationId()).orElse(null);
                if (specification == null) {
                    log.warn("Specification {} not found for exam {}", exam.getSpecificationId(), examId);
                }
            }

            // 4. Load all questions for this exam
            List<ExamQuestion> allQuestions = examQuestionRepository.findByExamId(examId);
            Map<Long, ExamQuestion> questionMap = allQuestions.stream()
                    .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));

            BigDecimal maxScore = allQuestions.stream()
                    .map(ExamQuestion::getPoints)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int totalQuestions = allQuestions.size();

            // 5. Load student's submissions for this attempt
            List<ExamSubmission> submissions = examSubmissionRepository
                    .findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber);
            Map<Long, ExamSubmission> submissionByQuestionId = submissions.stream()
                    .collect(Collectors.toMap(ExamSubmission::getQuestionId, Function.identity()));

            String schemaName = String.format("exam_%d_student_%d", examId, studentId);
            String teacherSchemaName = schemaName + "_teacher";

            // 6. Reset schemas before grading
            log.info("Resetting schema [{}] before grading", schemaName);
            examSchemaService.resetSchema(schemaName);

            log.info("Setting up teacher schema [{}] for test case validation", teacherSchemaName);
            examSchemaService.resetSchema(teacherSchemaName);
            if (specification != null && specification.getDdlScript() != null) {
                examSchemaService.loadTemplateIntoSchema(teacherSchemaName, specification.getDdlScript(), null);
            }

            // 7. Grade ALL questions sequentially (order by orderIndex)
            BigDecimal totalScore = BigDecimal.ZERO;
            int correctCount = 0;

            List<ExamQuestion> sortedQuestions = allQuestions.stream()
                    .sorted(Comparator.comparingInt(ExamQuestion::getOrderIndex))
                    .toList();

            for (ExamQuestion question : sortedQuestions) {
                ExamSubmission submission = submissionByQuestionId.get(question.getId());
                String studentQuery = (submission != null) ? submission.getStudentQuery() : null;

                boolean isCorrect = false;
                String errorMessage = null;
                int executionTimeMs = 0;

                if (studentQuery == null || studentQuery.isBlank()) {
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
                            examSchemaService.executeSql(schemaName, studentQuery);
                            isCorrect = gradeAnswer(schemaName, teacherSchemaName, question, submission);
                            if (!isCorrect && errorMessage == null) {
                                errorMessage = submission.getErrorMessage() != null ? submission.getErrorMessage() : "Answer did not match expected result";
                            }
                        }
                        executionTimeMs = (int) (System.currentTimeMillis() - startTime);
                    } catch (Exception e) {
                        executionTimeMs = (int) (System.currentTimeMillis() - startTime);
                        errorMessage = e.getMessage();
                        log.warn("Q{} execution failed: {}", question.getId(), e.getMessage());
                    }
                }

                BigDecimal scoreEarned;
                if (question.getQuestionType() == QuestionType.CREATE_TABLE || question.getQuestionType() == QuestionType.INSERT_DATA) {
                    scoreEarned = submission.getScoreEarned() != null ? submission.getScoreEarned() : (isCorrect ? question.getPoints() : BigDecimal.ZERO);
                } else {
                    scoreEarned = isCorrect ? question.getPoints() : BigDecimal.ZERO;
                }
                
                if (isCorrect || (scoreEarned.compareTo(BigDecimal.ZERO) > 0 && scoreEarned.compareTo(question.getPoints()) == 0)) {
                    correctCount++;
                    isCorrect = true;
                }
                totalScore = totalScore.add(scoreEarned);

                // Update submission with grading result
                if (submission != null) {
                    submission.setIsCorrect(isCorrect);
                    submission.setScoreEarned(scoreEarned);
                    submission.setErrorMessage(errorMessage);
                    submission.setExecutionTimeMs(executionTimeMs);
                    submission.setStatus(SubmissionStatus.GRADED);
                    examSubmissionRepository.save(submission);
                }
            }

            // 8. Update ExamResult with final scores and COMPLETED status
            existingResult.setTotalScore(totalScore);
            existingResult.setMaxScore(maxScore);
            existingResult.setTotalQuestions(totalQuestions);
            existingResult.setCorrectCount(correctCount);
            existingResult.setStatus(GradingStatus.COMPLETED);
            examResultRepository.save(existingResult);

            // 9. Cleanup — drop schemas after grading
            try {
                examSchemaService.dropSchema(schemaName);
                examSchemaService.dropSchema(teacherSchemaName);
            } catch (Exception e) {
                log.warn("Failed to drop schemas [{}] after grading: {}", schemaName, e.getMessage());
            }

            // 10. End session — release Redis session lock
            try {
                examSessionService.endSession(examId, studentId);
            } catch (Exception e) {
                log.warn("Failed to end session for exam={}, student={}: {}", examId, studentId, e.getMessage());
            }

            // 11. Notify student via WebSocket
            gradingNotificationService.notifyGradingCompleted(
                    examId, studentId, totalScore, maxScore, correctCount, totalQuestions, LocalDateTime.now());

            log.info("Grading completed: exam={}, student={}, score={}/{}", examId, studentId, totalScore, maxScore);

            // 12. Notify teacher via WebSocket
            User student = userRepository.findById(studentId).orElse(null);
            String studentName = (student != null) ? student.getFullName() : "Unknown";
            gradingNotificationService.notifyTeacherGradingCompleted(
                    examId, exam.getTitle(), studentId, studentName, totalScore, maxScore);

        } catch (Exception e) {
            log.error("Grading FAILED: exam={}, student={}, attempt={}: {}",
                    examId, studentId, attemptNumber, e.getMessage(), e);

            // Mark result as FAILED
            existingResult.setStatus(GradingStatus.FAILED);
            examResultRepository.save(existingResult);

            // Notify student about failure
            gradingNotificationService.notifyGradingFailed(examId, studentId, e.getMessage());
        }
    }

    // ========== Grading Logic (extracted from old SubmitExamUsecase) ==========

    private boolean gradeAnswer(String schemaName, String teacherSchemaName, ExamQuestion question, ExamSubmission submission) {
        QuestionType type = question.getQuestionType();
        switch (type) {
            case CREATE_TABLE:
            case INSERT_DATA:
                return gradeByTestCases(schemaName, teacherSchemaName, question, submission);
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
                    schemaName, specification.getDdlScript(), null,
                    "fallback-no-dataset", question, studentQuery);
        }

        for (SpecDataset dataset : activeDatasets) {
            String datasetLabel = "dataset[" + dataset.getId() + ":" + dataset.getName() + "]";
            GradeDecision decision = gradeSelectWithSingleDataset(
                    schemaName, specification.getDdlScript(), dataset.getDataScript(),
                    datasetLabel, question, studentQuery);
            if (!decision.isCorrect()) {
                return decision;
            }
        }

        return GradeDecision.pass();
    }

    private GradeDecision gradeSelectWithSingleDataset(
            String schemaName, String ddlScript, String datasetScript,
            String datasetLabel, ExamQuestion question, String studentQuery) {
        try {
            examSchemaService.resetSchema(schemaName);
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, datasetScript);

            List<Map<String, Object>> actual = examSchemaService.executeSql(schemaName, studentQuery);
            List<Map<String, Object>> expected = examSchemaService.executeSql(schemaName, question.getCorrectQuery());

            boolean requireStrictOrder = question.getCorrectQuery() != null 
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            if (!compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                return GradeDecision.fail("SELECT result mismatch on " + datasetLabel);
            }
            return GradeDecision.pass();
        } catch (Exception e) {
            return GradeDecision.fail("Failed on " + datasetLabel + ": " + e.getMessage());
        }
    }

    private record GradeDecision(boolean isCorrect, String errorMessage) {
        static GradeDecision pass() { return new GradeDecision(true, null); }
        static GradeDecision fail(String message) { return new GradeDecision(false, message); }
    }

    private boolean gradeByTestCases(String schemaName, String teacherSchemaName, ExamQuestion question, ExamSubmission submission) {
        List<TestCase> testCases = testCaseRepository.findByQuestionId(question.getId());
        
        if (testCases == null || testCases.isEmpty()) {
            return gradeByStrictComparison(schemaName, question);
        }

        BigDecimal earnedTotal = BigDecimal.ZERO;
        boolean allPassed = true;
        StringBuilder errorBuilder = new StringBuilder();

        for (TestCase tc : testCases) {
            try {
                String validationQuery = tc.getValidationQuery()
                                           .replace("{SCHEMA}", schemaName)
                                           .replace("{TEACHER_SCHEMA}", teacherSchemaName);
                
                List<Map<String, Object>> actual = examSchemaService.executeAdminSql(validationQuery);
                
                boolean isTcCorrect = false;
                
                if (tc.getExpectedValue() != null) {
                    String expectedStr = tc.getExpectedValue().trim();
                    if (actual != null && actual.size() == 1 && actual.get(0).size() == 1) {
                         Object firstVal = actual.get(0).values().iterator().next();
                         String actualStr = normalizeValue(firstVal);
                         if (actualStr.equalsIgnoreCase(expectedStr)) {
                             isTcCorrect = true;
                         }
                    } else if (actual != null && actual.isEmpty() && "0".equals(expectedStr)) {
                         isTcCorrect = true;
                    }
                }

                if (isTcCorrect) {
                     earnedTotal = earnedTotal.add(tc.getScoreWeight() != null ? tc.getScoreWeight() : BigDecimal.ZERO);
                } else {
                     allPassed = false;
                     errorBuilder.append(java.lang.String.format("Test case %d failed. ", tc.getOrderIndex() != null ? tc.getOrderIndex() : tc.getId()));
                }
            } catch (Exception e) {
                allPassed = false;
                errorBuilder.append(java.lang.String.format("Test case %d error: %s. ", tc.getOrderIndex() != null ? tc.getOrderIndex() : tc.getId(), e.getMessage()));
            }
        }

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }
        
        return allPassed;
    }

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

            boolean requireStrictOrder = question.getCorrectQuery() != null 
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            return compareResultSetsStrict(actual, expected, requireStrictOrder);
        } catch (Exception e) {
            log.warn("Strict grading failed for Q{}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    private boolean gradeByVerifyScript(String schemaName, ExamQuestion question) {
        String verifyScript = question.getVerifyScript();
        if (verifyScript == null || verifyScript.isBlank()) {
            log.warn("No verify_script for Q{}", question.getId());
            return false;
        }

        try {
            String resolvedScript = verifyScript.replace("{SCHEMA}", schemaName);

            List<Map<String, Object>> actual = examSchemaService.executeSql(
                    schemaName, resolvedScript);
            List<Map<String, Object>> expected = examSchemaService.executeSql(
                    schemaName, question.getCorrectQuery());

            boolean requireStrictOrder = question.getCorrectQuery() != null 
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            return compareResultSetsStrict(actual, expected, requireStrictOrder);
        } catch (Exception e) {
            log.warn("Verify script failed for Q{}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    private boolean compareResultSetsStrict(List<Map<String, Object>> actual,
            List<Map<String, Object>> expected, boolean requireStrictOrder) {
        if (actual == null || expected == null) return false;
        if (actual.size() != expected.size()) return false;

        List<String> actualRows = new ArrayList<>();
        for (Map<String, Object> row : actual) {
            StringBuilder sb = new StringBuilder();
            for (Object val : row.values()) {
                sb.append(normalizeValue(val)).append("|||");
            }
            actualRows.add(sb.toString().toLowerCase());
        }

        List<String> expectedRows = new ArrayList<>();
        for (Map<String, Object> row : expected) {
            StringBuilder sb = new StringBuilder();
            for (Object val : row.values()) {
                sb.append(normalizeValue(val)).append("|||");
            }
            expectedRows.add(sb.toString().toLowerCase());
        }

        if (!requireStrictOrder) {
            Collections.sort(actualRows);
            Collections.sort(expectedRows);
        }

        return actualRows.equals(expectedRows);
    }

    private String normalizeValue(Object value) {
        if (value == null) return "null";
        String str = value.toString().trim();
        if (str.matches("-?\\d+\\.\\d+")) {
            str = str.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return str;
    }
}
