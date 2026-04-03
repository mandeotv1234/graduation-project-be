package graduation_project_be.application.usecases;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TriggerMetadata;
import graduation_project_be.domain.models.TableMetadata.ColumnMetadata;
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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
    private final ObjectMapper objectMapper;

    @Transactional
    public void markSystemError(Long examId, Long studentId, int attemptNumber) {
        examResultRepository.findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber)
                .ifPresent(result -> {
                    result.setStatus(GradingStatus.SYSTEM_ERROR);
                    examResultRepository.save(result);
                    log.error("Marked exam result as SYSTEM_ERROR for exam={}, student={}, attempt={}", examId, studentId, attemptNumber);
                });
    }

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
                boolean hasExecutionError = false;

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
                            try {
                                examSchemaService.executeSql(schemaName, studentQuery);
                            } catch (Exception execErr) {
                                errorMessage = "Cảnh báo Lỗi Execute: " + execErr.getMessage();
                                hasExecutionError = true;
                            }
                            if (hasExecutionError && isCreateTableFailAllMode(question)) {
                                if (submission != null) {
                                    submission.setScoreEarned(BigDecimal.ZERO);
                                    submission.setErrorMessage(
                                            "SQL lỗi thực thi và rubric đang để FAIL_ALL nên câu này bị 0 điểm.");
                                }
                                isCorrect = false;
                            } else if (submission != null) {
                                isCorrect = gradeAnswer(schemaName, teacherSchemaName, question, submission);
                            }
                            
                            if (submission != null && submission.getErrorMessage() != null && !submission.getErrorMessage().isBlank()) {
                                if (errorMessage != null) {
                                    errorMessage = errorMessage + " | Lỗi cú pháp/Cấu trúc: " + submission.getErrorMessage();
                                } else {
                                    errorMessage = submission.getErrorMessage();
                                }
                            } else if (!isCorrect && errorMessage == null) {
                                errorMessage = "Kết quả không khớp với đáp án mẫu.";
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
                if (question.getQuestionType() == QuestionType.CREATE_TABLE
                        || question.getQuestionType() == QuestionType.INSERT_DATA
                        || question.getQuestionType() == QuestionType.STORED_PROCEDURE
                        || question.getQuestionType() == QuestionType.FUNCTION
                        || question.getQuestionType() == QuestionType.TRIGGER) {
                    // These types use algorithmic/partial grading — respect scoreEarned set by grader
                    scoreEarned = (submission != null && submission.getScoreEarned() != null)
                            ? submission.getScoreEarned()
                            : (isCorrect ? question.getPoints() : BigDecimal.ZERO);
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

            // Collect results as a generic structure for notification
            List<Map<String, Object>> resultsList = sortedQuestions.stream()
                .map(q -> {
                    ExamSubmission s = submissionByQuestionId.get(q.getId());
                    Map<String, Object> map = new HashMap<>();
                    map.put("submissionId", s != null ? s.getId() : null);
                    map.put("questionId", q.getId());
                    map.put("orderIndex", q.getOrderIndex());
                    map.put("studentQuery", s != null ? s.getStudentQuery() : "");
                    map.put("isCorrect", s != null && Boolean.TRUE.equals(s.getIsCorrect()));
                    map.put("scoreEarned", s != null ? s.getScoreEarned() : BigDecimal.ZERO);
                    map.put("maxPoints", q.getPoints());
                    map.put("errorMessage", s != null ? s.getErrorMessage() : "No submission");
                    map.put("executionTimeMs", s != null ? s.getExecutionTimeMs() : null);
                    return map;
                })
                .collect(Collectors.toList());

            String questionResultsJson = "";
            try {
                questionResultsJson = objectMapper.writeValueAsString(resultsList);
            } catch (Exception e) {
                log.warn("Failed to serialize question results to JSON for notification: {}", e.getMessage());
            }

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
                    examId, studentId, totalScore, maxScore, correctCount, totalQuestions, 
                    questionResultsJson, LocalDateTime.now());

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
                return gradeCreateTableAlgorithmic(schemaName, teacherSchemaName, question, submission);
            case INSERT_DATA:
                return gradeInsertDataAlgorithmic(schemaName, teacherSchemaName, question, submission);
            case TRIGGER:
                return gradeTriggerAlgorithmic(schemaName, teacherSchemaName, question, submission);
            case FUNCTION:
            case STORED_PROCEDURE:
                return gradeRoutineAlgorithmic(schemaName, teacherSchemaName, question, submission);
            default:
                log.warn("Unknown question type: {}", type);
                return false;
        }
    }

    private boolean gradeCreateTableAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question, ExamSubmission submission) {
        // === Check for rubric-based grading ===
        if (question.getGradingRubric() != null && !question.getGradingRubric().isBlank()) {
            try {
                return gradeCreateTableByRubric(schemaName, question, submission);
            } catch (Exception e) {
                log.warn("Rubric-based grading failed for Q{}, falling back to algorithmic: {}", question.getId(), e.getMessage());
            }
        }

        // === Fallback: existing algorithmic grading ===
        List<TableMetadata> expectedTables = examSchemaService.extractMetadata(teacherSchemaName);
        List<TableMetadata> actualTables = examSchemaService.extractMetadata(schemaName);

        if (expectedTables == null || expectedTables.isEmpty()) {
            return gradeByTestCases(schemaName, teacherSchemaName, question, submission);
        }

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        BigDecimal perTablePoints = totalPoints.divide(BigDecimal.valueOf(expectedTables.size()), 4, RoundingMode.HALF_UP);
        BigDecimal earnedTotal = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;

        for (TableMetadata expectedTable : expectedTables) {
            TableMetadata actualTable = actualTables.stream()
                    .filter(t -> t.getTableName().equalsIgnoreCase(expectedTable.getTableName()))
                    .findFirst().orElse(null);

            if (actualTable == null) {
                allPassed = false;
                errorBuilder.append(String.format("Thiếu bảng %s. ", expectedTable.getTableName()));
                continue;
            }

            int expectedColCount = expectedTable.getColumns().size();
            if (expectedColCount == 0) {
                earnedTotal = earnedTotal.add(perTablePoints);
                continue; 
            }
            
            double tableScore = 0.0;
            double perColScore = 1.0 / expectedColCount; 
            
            for (ColumnMetadata expectedCol : expectedTable.getColumns()) {
                 ColumnMetadata actualCol = actualTable.getColumns().stream()
                         .filter(c -> c.getColumnName().equalsIgnoreCase(expectedCol.getColumnName()))
                         .findFirst().orElse(null);
                 
                 if (actualCol != null) {
                      double colPoints = 0.5 * perColScore;
                      if (actualCol.getDataType().equalsIgnoreCase(expectedCol.getDataType())) {
                          colPoints += 0.3 * perColScore;
                      } else {
                          errorBuilder.append(String.format("Bảng %s: cột %s sai kiểu dữ liệu (Kỳ vọng: %s, Thực tế: %s). ", 
                              expectedTable.getTableName(), expectedCol.getColumnName(), expectedCol.getDataType(), actualCol.getDataType()));
                          allPassed = false;
                      }
                      if (actualCol.isPrimaryKey() == expectedCol.isPrimaryKey()) {
                          colPoints += 0.1 * perColScore;
                      } else {
                          if (expectedCol.isPrimaryKey()) {
                              errorBuilder.append(String.format("Bảng %s: thiếu khoá chính ở cột %s. ", expectedTable.getTableName(), expectedCol.getColumnName()));
                          } else {
                              errorBuilder.append(String.format("Bảng %s: dư định nghĩa khoá chính ở cột %s. ", expectedTable.getTableName(), expectedCol.getColumnName()));
                          }
                          allPassed = false;
                      }
                      if (expectedCol.isForeignKey()) {
                           if (actualCol.isForeignKey() && 
                               expectedCol.getReferencesTable().equalsIgnoreCase(actualCol.getReferencesTable())) {
                               colPoints += 0.1 * perColScore;
                           } else {
                               errorBuilder.append(String.format("Bảng %s, cột %s: thiếu khoá ngoại tham chiếu %s. ", expectedTable.getTableName(), expectedCol.getColumnName(), expectedCol.getReferencesTable()));
                               allPassed = false;
                           }
                      } else {
                           if (!actualCol.isForeignKey()) {
                               colPoints += 0.1 * perColScore;
                           } else {
                               errorBuilder.append(String.format("Bảng %s, cột %s: bị dư khoá ngoại sai đề. ", expectedTable.getTableName(), expectedCol.getColumnName()));
                               allPassed = false;
                           }
                      }
                      tableScore += colPoints;
                 } else {
                      allPassed = false;
                      errorBuilder.append(String.format("Bảng %s: thiếu cột %s. ", expectedTable.getTableName(), expectedCol.getColumnName()));
                 }
            }
            BigDecimal tblEarned = perTablePoints.multiply(BigDecimal.valueOf(tableScore));
            earnedTotal = earnedTotal.add(tblEarned);
        }

        if (allPassed) {
            earnedTotal = totalPoints;
        } else if (earnedTotal.compareTo(totalPoints) > 0) {
            earnedTotal = totalPoints;
        }

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }
        
        return allPassed;
    }

    private boolean isCreateTableFailAllMode(ExamQuestion question) {
        if (question == null || question.getQuestionType() != QuestionType.CREATE_TABLE) {
            return false;
        }
        if (question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return false;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(question.getGradingRubric());
            String action = root
                    .path("grading_payload")
                    .path("grading_settings")
                    .path("syntax_error_action")
                    .asText("PARTIAL");
            return "FAIL_ALL".equalsIgnoreCase(action);
        } catch (Exception e) {
            log.warn("Cannot parse syntax_error_action for Q{}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Grade CREATE TABLE using the JSON rubric stored in question.gradingRubric.
     * Extracts actual table metadata from the student schema and scores against rubric rules.
     */
    private boolean gradeCreateTableByRubric(String schemaName, ExamQuestion question, ExamSubmission submission) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode rubric;
        try {
            rubric = mapper.readTree(question.getGradingRubric());
        } catch (Exception e) {
            throw new RuntimeException("Invalid rubric JSON: " + e.getMessage());
        }

        List<TableMetadata> actualTables = examSchemaService.extractMetadata(schemaName);
        com.fasterxml.jackson.databind.JsonNode payload = rubric.path("grading_payload");
        com.fasterxml.jackson.databind.JsonNode settings = payload.path("grading_settings");
        boolean caseSensitive = settings.path("case_sensitive_names").asBoolean(false);
        boolean positiveOnlyScoring = settings.path("positive_only_scoring").asBoolean(false);
        boolean failAllMode = "FAIL_ALL".equalsIgnoreCase(
            settings.path("syntax_error_action").asText("PARTIAL"));

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        BigDecimal earnedTotal = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;

        com.fasterxml.jackson.databind.JsonNode tables = payload.path("tables");
        for (int i = 0; i < tables.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode rubricTable = tables.get(i);
            String expectedName = rubricTable.path("expected_name").asText("");
            double existencePoints = rubricTable.path("existence_points").asDouble(0);
            String missingAction = rubricTable.path("missing_penalty_action").asText("SKIP_TABLE");

            // Find actual table
            TableMetadata actualTable = actualTables.stream()
                    .filter(t -> caseSensitive
                            ? t.getTableName().equals(expectedName)
                            : t.getTableName().equalsIgnoreCase(expectedName))
                    .findFirst().orElse(null);

            if (actualTable == null) {
                allPassed = false;
                errorBuilder.append(String.format("Thiếu bảng %s. ", expectedName));
                if ("SKIP_TABLE".equals(missingAction)) {
                    continue; // 0 points for this table
                }
                continue;
            }

            // Table exists → award existence points
            earnedTotal = earnedTotal.add(BigDecimal.valueOf(existencePoints));

            // Grade columns
            com.fasterxml.jackson.databind.JsonNode rubricCols = rubricTable.path("columns");
            for (int j = 0; j < rubricCols.size(); j++) {
                com.fasterxml.jackson.databind.JsonNode rc = rubricCols.get(j);
                String colName = rc.path("name").asText("");
                String expectedType = rc.path("expected_type").asText("");
                double colPoints = rc.path("points").asDouble(0);
                double typePenalty = rc.path("type_mismatch_penalty").asDouble(0);

                ColumnMetadata actualCol = actualTable.getColumns().stream()
                        .filter(c -> caseSensitive
                                ? c.getColumnName().equals(colName)
                                : c.getColumnName().equalsIgnoreCase(colName))
                        .findFirst().orElse(null);

                if (actualCol == null) {
                    allPassed = false;
                    if (positiveOnlyScoring) {
                        errorBuilder.append(String.format("Bảng %s: thiếu cột %s (không cộng điểm mục này). ", expectedName, colName));
                    } else {
                        errorBuilder.append(String.format("Bảng %s: thiếu cột %s (-%s đ). ", expectedName, colName, colPoints));
                    }
                } else {
                    // Check type
                    boolean typeMatch = caseSensitive
                            ? actualCol.getRawDataType().equals(expectedType)
                            : actualCol.getRawDataType().equalsIgnoreCase(expectedType);
                    if (typeMatch) {
                        earnedTotal = earnedTotal.add(BigDecimal.valueOf(colPoints));
                    } else {
                        double awarded = positiveOnlyScoring
                                ? 0
                                : Math.max(0, colPoints - typePenalty);
                        earnedTotal = earnedTotal.add(BigDecimal.valueOf(awarded));
                        allPassed = false;
                        if (positiveOnlyScoring) {
                            errorBuilder.append(String.format("Bảng %s: cột %s sai kiểu (Kỳ vọng: %s, Thực tế: %s, không cộng điểm mục này). ",
                                    expectedName, colName, expectedType, actualCol.getDataType()));
                        } else {
                            errorBuilder.append(String.format("Bảng %s: cột %s sai kiểu (Kỳ vọng: %s, Thực tế: %s, -%s đ). ",
                                    expectedName, colName, expectedType, actualCol.getDataType(), typePenalty));
                        }
                    }
                }
            }

            // Grade constraints
            com.fasterxml.jackson.databind.JsonNode rubricConstraints = rubricTable.path("constraints");
            for (int j = 0; j < rubricConstraints.size(); j++) {
                com.fasterxml.jackson.databind.JsonNode rc = rubricConstraints.get(j);
                String cType = rc.path("type").asText("");
                double cPoints = rc.path("points").asDouble(0);
                double cPenalty = rc.path("missing_penalty").asDouble(0);

                boolean constraintFound = false;

                switch (cType) {
                    case "PRIMARY_KEY":
                        com.fasterxml.jackson.databind.JsonNode pkCols = rc.path("columns");
                        constraintFound = true;
                        for (int k = 0; k < pkCols.size(); k++) {
                            String pkColName = pkCols.get(k).asText();
                            boolean pkMatch = actualTable.getColumns().stream()
                                    .anyMatch(c -> (caseSensitive
                                            ? c.getColumnName().equals(pkColName)
                                            : c.getColumnName().equalsIgnoreCase(pkColName))
                                            && c.isPrimaryKey());
                            if (!pkMatch) {
                                constraintFound = false;
                                break;
                            }
                        }
                        break;
                    case "FOREIGN_KEY":
                        String refTable = rc.path("references_table").asText("");
                        com.fasterxml.jackson.databind.JsonNode fkCols = rc.path("columns");
                        constraintFound = true;
                        for (int k = 0; k < fkCols.size(); k++) {
                            String fkColName = fkCols.get(k).asText();
                            boolean fkMatch = actualTable.getColumns().stream()
                                    .anyMatch(c -> (caseSensitive
                                            ? c.getColumnName().equals(fkColName)
                                            : c.getColumnName().equalsIgnoreCase(fkColName))
                                            && c.isForeignKey()
                                            && (caseSensitive
                                                ? refTable.equals(c.getReferencesTable())
                                                : refTable.equalsIgnoreCase(c.getReferencesTable())));
                            if (!fkMatch) {
                                constraintFound = false;
                                break;
                            }
                        }
                        break;
                    default:
                        // UNIQUE, CHECK, DEFAULT — cannot easily verify from metadata alone, assume present
                        constraintFound = true;
                        break;
                }

                if (constraintFound) {
                    earnedTotal = earnedTotal.add(BigDecimal.valueOf(cPoints));
                } else {
                    allPassed = false;
                    double awarded = positiveOnlyScoring
                            ? 0
                            : Math.max(0, cPoints - cPenalty);
                    earnedTotal = earnedTotal.add(BigDecimal.valueOf(awarded));
                    if (positiveOnlyScoring) {
                        errorBuilder.append(String.format("Bảng %s: thiếu ràng buộc %s (không cộng điểm mục này). ", expectedName, cType));
                    } else {
                        errorBuilder.append(String.format("Bảng %s: thiếu ràng buộc %s (-%s đ). ", expectedName, cType, cPenalty));
                    }
                }
            }
        }

        earnedTotal = earnedTotal.setScale(2, RoundingMode.HALF_UP);
        if (failAllMode && !allPassed) {
            earnedTotal = BigDecimal.ZERO;
            if (errorBuilder.length() > 0) {
                errorBuilder.insert(0, "Rubric đang để FAIL_ALL: có lỗi nên câu này bị 0 điểm toàn bộ. ");
            } else {
                errorBuilder.append("Rubric đang để FAIL_ALL: có lỗi nên câu này bị 0 điểm toàn bộ.");
            }
        }
        if (earnedTotal.compareTo(totalPoints) > 0) earnedTotal = totalPoints;
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0) earnedTotal = BigDecimal.ZERO;

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        return allPassed;
    }

    private boolean gradeInsertDataAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question, ExamSubmission submission) {
        // === Check for rubric-based grading ===
        if (question.getGradingRubric() != null && !question.getGradingRubric().isBlank()) {
            try {
                return gradeInsertDataByRubric(schemaName, question, submission);
            } catch (Exception e) {
                log.warn("Rubric-based grading failed for Q{}, falling back to algorithmic (legacy): {}", question.getId(), e.getMessage());
            }
        }

        // === Fallback: existing algorithmic grading ===
        List<TableMetadata> expectedTables = examSchemaService.extractMetadata(teacherSchemaName);

        if (expectedTables == null || expectedTables.isEmpty()) {
            return gradeByTestCases(schemaName, teacherSchemaName, question, submission);
        }

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        BigDecimal perTablePoints = totalPoints.divide(BigDecimal.valueOf(expectedTables.size()), 4, RoundingMode.HALF_UP);
        BigDecimal earnedTotal = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;

        for (TableMetadata expectedTable : expectedTables) {
            String tName = expectedTable.getTableName();
            
            try {
                // 1. teacher count
                List<Map<String, Object>> tcRes = examSchemaService.executeAdminSql("SELECT COUNT(*) as cnt FROM [" + teacherSchemaName + "]." + tName).getResultSet();
                long tCount = ((Number) tcRes.get(0).values().iterator().next()).longValue();
                
                if (tCount == 0) {
                     earnedTotal = earnedTotal.add(perTablePoints); // table not required to have data
                     continue;
                }

                // 2. student count
                long sCount = 0;
                try {
                    List<Map<String, Object>> scRes = examSchemaService.executeAdminSql("SELECT COUNT(*) as cnt FROM [" + schemaName + "]." + tName).getResultSet();
                    sCount = ((Number) scRes.get(0).values().iterator().next()).longValue();
                } catch (Exception e) {
                    allPassed = false;
                    errorBuilder.append(String.format("Bảng %s lỗi trống rỗng hoặc chưa được tạo. ", tName));
                    continue; // 0 points for this table
                }

                // 3. missing count
                long missingCount = 0;
                try {
                    String missingSql = "SELECT COUNT(*) FROM (SELECT * FROM [" + teacherSchemaName + "]." + tName + " EXCEPT SELECT * FROM [" + schemaName + "]." + tName + ") a";
                    List<Map<String, Object>> mRes = examSchemaService.executeAdminSql(missingSql).getResultSet();
                    missingCount = ((Number) mRes.get(0).values().iterator().next()).longValue();
                } catch (Exception e) {
                    missingCount = tCount; // fallback
                }

                // 4. extra count
                long extraCount = 0;
                try {
                    String extraSql = "SELECT COUNT(*) FROM (SELECT * FROM [" + schemaName + "]." + tName + " EXCEPT SELECT * FROM [" + teacherSchemaName + "]." + tName + ") b";
                    List<Map<String, Object>> eRes = examSchemaService.executeAdminSql(extraSql).getResultSet();
                    extraCount = ((Number) eRes.get(0).values().iterator().next()).longValue();
                } catch (Exception e) {
                    extraCount = 0;
                }

                if (missingCount == 0 && extraCount == 0 && sCount == tCount) {
                    earnedTotal = earnedTotal.add(perTablePoints);
                } else {
                    allPassed = false;
                    long correctRows = Math.max(0, tCount - missingCount);
                    // Penalize extra wrong rows
                    long finalCorrect = Math.max(0, correctRows - extraCount);
                    
                    double ratio = (double) finalCorrect / tCount;
                    earnedTotal = earnedTotal.add(perTablePoints.multiply(BigDecimal.valueOf(ratio)));
                    errorBuilder.append(String.format("Bảng %s: thiếu %d dòng, dư/sai %d dòng. ", tName, missingCount, extraCount));
                }
            } catch (Exception e) {
                log.warn("Failed to grade Insert data algorithmically for table {}", tName, e);
                allPassed = false;
                errorBuilder.append(String.format("Lỗi hệ thống khi chấm bảng %s. ", tName));
            }
        }

        if (earnedTotal.compareTo(totalPoints) > 0) earnedTotal = totalPoints;

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }
        
        return allPassed;
    }

    public boolean gradeInsertDataByRubric(String schemaName, ExamQuestion question, ExamSubmission submission) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(question.getGradingRubric());
        } catch (Exception e) {
            throw new RuntimeException("Invalid rubric JSON: " + e.getMessage());
        }

        JsonNode payload = rubric.path("grading_payload");
        JsonNode settings = payload.path("grading_settings");
        boolean trimSpaces = readBooleanSetting(settings.path("trim_string_spaces"), true);
        boolean caseInsensitive = readBooleanSetting(settings.path("case_insensitive_data"), false);
        boolean allowExtraRows = readBooleanSetting(settings.path("allow_extra_rows"), false);
        double penaltyPerExtraRow = Math.max(0d, readDoubleSetting(settings.path("penalty_per_extra_row"), 0.1d));
        boolean failAllMode = "FAIL_ALL".equalsIgnoreCase(settings.path("syntax_error_action").asText("PARTIAL"));

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        BigDecimal earnedTotal = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;

        JsonNode datasets = payload.path("expected_datasets");
        if (datasets.isMissingNode()) {
            datasets = payload.path("tables"); // Fallback to older format if 'expected_datasets' not used
        }

        for (int i = 0; i < datasets.size(); i++) {
            JsonNode dataset = datasets.get(i);
            String tableName = dataset.path("table_name").asText();
            double tablePoints = dataset.path("table_points").asDouble(0);
            double pointsPerRow = dataset.path("points_per_row").asDouble(0);
            
            JsonNode pksNode = dataset.path("primary_keys");
            List<String> primaryKeys = new ArrayList<>();
            for (int j = 0; j < pksNode.size(); j++) {
                primaryKeys.add(pksNode.get(j).asText());
            }

            JsonNode columnsToGradeNode = dataset.path("columns_to_grade");
            List<String> columnsToGrade = new ArrayList<>();
            for (int j = 0; j < columnsToGradeNode.size(); j++) {
                columnsToGrade.add(columnsToGradeNode.get(j).asText());
            }

            JsonNode expectedRows = dataset.path("rows");
            if (expectedRows == null || expectedRows.size() == 0 || expectedRows.isMissingNode()) {
                earnedTotal = earnedTotal.add(BigDecimal.valueOf(tablePoints));
                continue;
            }

            // Retrieve all rows from the student's schema for this table
            List<Map<String, Object>> actualRows = new ArrayList<>();
            try {
                actualRows = examSchemaService.executeAdminSql("SELECT * FROM [" + schemaName + "]." + tableName).getResultSet();
            } catch (Exception e) {
                allPassed = false;
                errorBuilder.append(String.format("Bảng %s bị lỗi hoặc không tồn tại. ", tableName));
                continue; // 0 points for this table
            }

            double earnedTable = 0;
            boolean[] usedActualRows = new boolean[actualRows.size()];

            for (int j = 0; j < expectedRows.size(); j++) {
                JsonNode expectedRow = expectedRows.get(j);

                // Find matching row by primary key
                Map<String, Object> actualRow = null;
                int actualRowIdx = -1;

                for (int idx = 0; idx < actualRows.size(); idx++) {
                    if (usedActualRows[idx]) {
                        continue;
                    }

                    Map<String, Object> candidate = actualRows.get(idx);
                    boolean pkMatch = true;
                    if (primaryKeys.isEmpty()) {
                        pkMatch = true; // no pk defined, compare directly (not recommended)
                    } else {
                        for (String pk : primaryKeys) {
                            String cVal = normalizeValueStr(candidate.get(pk), trimSpaces, caseInsensitive);
                            String eVal = normalizeValueStr(expectedRow.path(pk).isNull() ? null : expectedRow.path(pk).asText(), trimSpaces, caseInsensitive);
                            if (!valuesEqual(cVal, eVal)) {
                                pkMatch = false;
                                break;
                            }
                        }
                    }

                    if (pkMatch) {
                        actualRow = candidate;
                        actualRowIdx = idx;
                        break;
                    }
                }

                if (actualRow == null) {
                    allPassed = false;
                    List<String> pkVals = primaryKeys.stream()
                        .map(pk -> pk + "=" + expectedRow.path(pk).asText())
                        .toList();
                    errorBuilder.append(String.format("Bảng %s: Thiếu dòng có %s. ", tableName, String.join(", ", pkVals)));
                    continue;
                }

                usedActualRows[actualRowIdx] = true;

                // Row found, check cols
                boolean rowMatch = true;
                List<String> wrongCols = new ArrayList<>();

                for (String col : columnsToGrade) {
                    String aVal = normalizeValueStr(actualRow.get(col), trimSpaces, caseInsensitive);
                    String eVal = normalizeValueStr(expectedRow.path(col).isNull() ? null : expectedRow.path(col).asText(), trimSpaces, caseInsensitive);
                    if (!valuesEqual(aVal, eVal)) {
                        rowMatch = false;
                        wrongCols.add(col + " (Kỳ vọng: " + eVal + ", Thực tế: " + aVal + ")");
                    }
                }

                if (rowMatch) {
                    earnedTable += pointsPerRow;
                } else {
                    allPassed = false;
                    List<String> pkVals = primaryKeys.stream()
                        .map(pk -> pk + "=" + expectedRow.path(pk).asText())
                        .toList();
                    errorBuilder.append(String.format("Bảng %s: Dòng %s có cột bị sai: %s. ", tableName, String.join(", ", pkVals), String.join("; ", wrongCols)));
                }
            }

            int extraRows = 0;
            for (boolean used : usedActualRows) {
                if (!used) {
                    extraRows++;
                }
            }

            if (extraRows > 0) {
                allPassed = false;
                if (allowExtraRows) {
                    double penalty = tablePoints * penaltyPerExtraRow * extraRows;
                    earnedTable = Math.max(0, earnedTable - penalty);
                    errorBuilder.append(String.format(
                            "Bảng %s: dư %d dòng, bị trừ %.2f điểm (allow_extra_rows=true). ",
                            tableName,
                            extraRows,
                            penalty));
                } else {
                    earnedTable = 0;
                    errorBuilder.append(String.format(
                            "Bảng %s: dư %d dòng và không cho phép dư dòng (allow_extra_rows=false). ",
                            tableName,
                            extraRows));
                }
            }

            earnedTotal = earnedTotal.add(BigDecimal.valueOf(earnedTable));
        }

        earnedTotal = earnedTotal.setScale(2, RoundingMode.HALF_UP);
        if (failAllMode && !allPassed) {
            earnedTotal = BigDecimal.ZERO;
            if (errorBuilder.length() > 0) {
                errorBuilder.insert(0, "Rubric đang để FAIL_ALL: có lỗi nên câu này bị 0 điểm toàn bộ. ");
            } else {
                errorBuilder.append("Rubric đang để FAIL_ALL: có lỗi nên câu này bị 0 điểm toàn bộ.");
            }
        }
        if (earnedTotal.compareTo(totalPoints) > 0) earnedTotal = totalPoints;
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0) earnedTotal = BigDecimal.ZERO;

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        return allPassed;
    }

    private String normalizeValueStr(Object val, boolean trimSpaces, boolean caseInsensitive) {
        if (val == null) return null;
        String s = String.valueOf(val);
        if (trimSpaces) s = s.trim();
        if (caseInsensitive) s = s.toLowerCase();
        return s;
    }

    private boolean valuesEqual(String actual, String expected) {
        if (java.util.Objects.equals(actual, expected)) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }

        BigDecimal aNum = parseDecimal(actual);
        BigDecimal eNum = parseDecimal(expected);
        if (aNum != null && eNum != null) {
            return aNum.compareTo(eNum) == 0;
        }

        return false;
    }

    private BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean readBooleanSetting(JsonNode node, boolean defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asInt() != 0;
        }
        if (node.isTextual()) {
            String value = node.asText("").trim().toLowerCase();
            if ("true".equals(value) || "1".equals(value) || "yes".equals(value) || "y".equals(value) || "on".equals(value)) {
                return true;
            }
            if ("false".equals(value) || "0".equals(value) || "no".equals(value) || "n".equals(value) || "off".equals(value)) {
                return false;
            }
        }
        return defaultValue;
    }

    private double readDoubleSetting(JsonNode node, double defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().trim());
            } catch (Exception ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
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

            List<Map<String, Object>> actual = examSchemaService.executeSql(schemaName, studentQuery).getResultSet();
            List<Map<String, Object>> expected = examSchemaService.executeSql(schemaName, question.getCorrectQuery()).getResultSet();

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
        log.info("[gradeByTestCases] Q{} has {} test cases", question.getId(), testCases == null ? 0 : testCases.size());
        
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
                log.info("[gradeByTestCases] Q{} TC{} running query: {}", question.getId(), tc.getOrderIndex(), validationQuery);
                
                List<Map<String, Object>> actual = examSchemaService.executeAdminSql(validationQuery).getResultSet();
                log.info("[gradeByTestCases] Q{} TC{} actual result: {}", question.getId(), tc.getOrderIndex(), actual);
                
                boolean isTcCorrect = false;
                
                if (tc.getExpectedValue() == null) {
                    // null expectedValue = just verify the query ran without error and returned some result
                    isTcCorrect = actual != null && !actual.isEmpty()
                            && actual.get(0).values().stream().anyMatch(v -> v != null);
                    log.info("[gradeByTestCases] Q{} TC{} null-expected check, result non-null: {}", question.getId(), tc.getOrderIndex(), isTcCorrect);
                } else if (tc.getExpectedValue() != null) {
                    String expectedStr = tc.getExpectedValue().trim();
                    if (actual != null && actual.size() == 1 && actual.get(0).size() == 1) {
                         Object firstVal = actual.get(0).values().iterator().next();
                         String actualStr = normalizeValue(firstVal);
                         log.info("[gradeByTestCases] Q{} TC{} compare: actual='{}' expected='{}'", question.getId(), tc.getOrderIndex(), actualStr, expectedStr);
                         if (actualStr.equalsIgnoreCase(expectedStr)) {
                             isTcCorrect = true;
                         }
                    } else if (actual != null && actual.isEmpty() && "0".equals(expectedStr)) {
                         isTcCorrect = true;
                    } else {
                         log.warn("[gradeByTestCases] Q{} TC{} unexpected result shape: rows={}, expected='{}'",
                                 question.getId(), tc.getOrderIndex(),
                                 actual == null ? "null" : actual.size(), expectedStr);
                    }
                }

                if (isTcCorrect) {
                     earnedTotal = earnedTotal.add(tc.getScoreWeight() != null ? tc.getScoreWeight() : BigDecimal.ZERO);
                     log.info("[gradeByTestCases] Q{} TC{} PASSED, earnedTotal={}", question.getId(), tc.getOrderIndex(), earnedTotal);
                } else {
                     allPassed = false;
                     errorBuilder.append(java.lang.String.format("Test case %d failed. ", tc.getOrderIndex() != null ? tc.getOrderIndex() : tc.getId()));
                     log.warn("[gradeByTestCases] Q{} TC{} FAILED", question.getId(), tc.getOrderIndex());
                }
            } catch (Exception e) {
                allPassed = false;
                errorBuilder.append(java.lang.String.format("Test case %d error: %s. ", tc.getOrderIndex() != null ? tc.getOrderIndex() : tc.getId(), e.getMessage()));
                log.error("[gradeByTestCases] Q{} TC{} threw exception: {}", question.getId(), tc.getOrderIndex(), e.getMessage(), e);
            }
        }
        log.info("[gradeByTestCases] Q{} allPassed={} earnedTotal={}", question.getId(), allPassed, earnedTotal);

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
                    schemaName, question.getCorrectQuery()).getResultSet();

            String verifyScript = question.getVerifyScript();
            if (verifyScript == null || verifyScript.isBlank()) {
                log.warn("No verify_script for Q{}, cannot do strict comparison", question.getId());
                return actual != null && !actual.isEmpty();
            }

            List<Map<String, Object>> expected = examSchemaService.executeSql(
                    schemaName, verifyScript).getResultSet();

            boolean requireStrictOrder = question.getCorrectQuery() != null 
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            return compareResultSetsStrict(actual, expected, requireStrictOrder);
        } catch (Exception e) {
            log.warn("Strict grading failed for Q{}: {}", question.getId(), e.getMessage());
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

    private boolean gradeRoutineAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question, ExamSubmission submission) {
        List<RoutineMetadata> expectedRoutines = examSchemaService.extractRoutineMetadata(teacherSchemaName);
        List<RoutineMetadata> actualRoutines = examSchemaService.extractRoutineMetadata(schemaName);

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        boolean hasTestCases = !testCaseRepository.findByQuestionId(question.getId()).isEmpty();

        // If teacher schema has no routines (DDL-only), grade purely by test cases (100% weight)
        if (expectedRoutines == null || expectedRoutines.isEmpty()) {
            if (!hasTestCases) {
                // Nothing to grade against
                log.warn("No routine metadata and no test cases for Q{}", question.getId());
                return false;
            }
            boolean passed = gradeByTestCases(schemaName, teacherSchemaName, question, submission);
            // gradeByTestCases already sets scoreEarned based on scoreWeight sum (0..1 range)
            // Scale up to totalPoints
            if (submission != null && submission.getScoreEarned() != null) {
                BigDecimal scaled = totalPoints.multiply(submission.getScoreEarned());
                if (scaled.compareTo(totalPoints) > 0) scaled = totalPoints;
                submission.setScoreEarned(scaled);
            }
            return passed;
        }

        // Metadata check is worth 20% if there are test cases, otherwise 100%
        BigDecimal metadataWeight = hasTestCases ? new BigDecimal("0.20") : BigDecimal.ONE;
        BigDecimal testCaseWeight = hasTestCases ? new BigDecimal("0.80") : BigDecimal.ZERO;

        BigDecimal maxMetadataScore = totalPoints.multiply(metadataWeight);
        BigDecimal perRoutineMax = maxMetadataScore.divide(BigDecimal.valueOf(expectedRoutines.size()), 4, RoundingMode.HALF_UP);
        BigDecimal earnedMetadataScore = BigDecimal.ZERO;
        
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassedMetadata = true;

        for (RoutineMetadata expected : expectedRoutines) {
            RoutineMetadata actual = actualRoutines.stream()
                .filter(r -> r.getRoutineName().equalsIgnoreCase(expected.getRoutineName()))
                .findFirst().orElse(null);

            if (actual == null) {
                allPassedMetadata = false;
                errorBuilder.append(String.format("Thiếu %s %s. ", expected.getRoutineType(), expected.getRoutineName()));
                continue;
            }
            
            double score = 0.5; // Found it

            // Type check (Procedure vs Function)
            if (expected.getRoutineType().equalsIgnoreCase(actual.getRoutineType())) {
                score += 0.2;
            } else {
                errorBuilder.append(String.format("Sai loại Routine %s (Kỳ vọng: %s). ", expected.getRoutineName(), expected.getRoutineType()));
                allPassedMetadata = false;
            }

            // Params check
            if (expected.getParameters().size() == actual.getParameters().size()) {
                score += 0.3;
            } else {
                errorBuilder.append(String.format("%s %s sai số lượng Parameters. ", expected.getRoutineType(), expected.getRoutineName()));
                allPassedMetadata = false;
            }
            
            earnedMetadataScore = earnedMetadataScore.add(perRoutineMax.multiply(BigDecimal.valueOf(score)));
        }

        if (allPassedMetadata) earnedMetadataScore = maxMetadataScore;

        BigDecimal earnedTestCaseScore = BigDecimal.ZERO;
        boolean testCasesPassed = true;

        if (hasTestCases) {
            testCasesPassed = gradeByTestCases(schemaName, teacherSchemaName, question, submission);
            // gradeByTestCases sets scoreEarned as sum of scoreWeights (0..1 ratio)
            // Multiply by maxTestCaseScore (= totalPoints * testCaseWeight)
            if (submission != null && submission.getScoreEarned() != null) {
                BigDecimal maxTestCaseScore = totalPoints.multiply(testCaseWeight);
                earnedTestCaseScore = maxTestCaseScore.multiply(submission.getScoreEarned());
            }
        }

        BigDecimal finalScore = earnedMetadataScore.add(earnedTestCaseScore);
        if (finalScore.compareTo(totalPoints) > 0) finalScore = totalPoints;

        if (submission != null) {
            submission.setScoreEarned(finalScore);
            boolean totalPassed = allPassedMetadata && testCasesPassed;
            if (!totalPassed) {
                String tcError = submission.getErrorMessage() != null ? submission.getErrorMessage() : "";
                submission.setErrorMessage((errorBuilder.toString().trim() + " " + tcError).trim());
            }
        }

        return allPassedMetadata && testCasesPassed;
    }

    private boolean gradeTriggerAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question, ExamSubmission submission) {
        java.util.List<TriggerMetadata> expectedTriggers = examSchemaService.extractTriggerMetadata(teacherSchemaName);
        java.util.List<TriggerMetadata> actualTriggers = examSchemaService.extractTriggerMetadata(schemaName);

        if (expectedTriggers == null || expectedTriggers.isEmpty()) {
            return gradeByTestCases(schemaName, teacherSchemaName, question, submission);
        }

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        boolean hasTestCases = !testCaseRepository.findByQuestionId(question.getId()).isEmpty();

        BigDecimal metadataWeight = hasTestCases ? new BigDecimal("0.20") : BigDecimal.ONE;
        BigDecimal testCaseWeight = hasTestCases ? new BigDecimal("0.80") : BigDecimal.ZERO;

        BigDecimal maxMetadataScore = totalPoints.multiply(metadataWeight);
        BigDecimal perTriggerMax = maxMetadataScore.divide(BigDecimal.valueOf(expectedTriggers.size()), 4, RoundingMode.HALF_UP);
        BigDecimal earnedMetadataScore = BigDecimal.ZERO;
        
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassedMetadata = true;

        for (TriggerMetadata expected : expectedTriggers) {
            TriggerMetadata actual = actualTriggers.stream()
                .filter(t -> t.getTriggerName().equalsIgnoreCase(expected.getTriggerName()))
                .findFirst().orElse(null);

            if (actual == null) {
                allPassedMetadata = false;
                errorBuilder.append(String.format("Thiếu Trigger %s trên bảng %s. ", expected.getTriggerName(), expected.getTableName()));
                continue;
            }

            double score = 0.4;

            if (actual.getTableName().equalsIgnoreCase(expected.getTableName())) score += 0.2;
            else {
                 allPassedMetadata = false;
                 errorBuilder.append(String.format("Trigger %s gắn sai bảng. ", expected.getTriggerName()));
            }

            if (expected.isInsert() == actual.isInsert() && expected.isUpdate() == actual.isUpdate() && expected.isDelete() == actual.isDelete()) {
                score += 0.2;
            } else {
                 allPassedMetadata = false;
                 errorBuilder.append(String.format("Trigger %s bắt sai Event (INSERT/UPDATE/DELETE). ", expected.getTriggerName()));
            }

            if (expected.isAfter() == actual.isAfter()) {
                score += 0.2;
            } else {
                 allPassedMetadata = false;
                 errorBuilder.append(String.format("Trigger %s sai Timing (AFTER/INSTEAD OF). ", expected.getTriggerName()));
            }

            earnedMetadataScore = earnedMetadataScore.add(perTriggerMax.multiply(BigDecimal.valueOf(score)));
        }

        if (allPassedMetadata) earnedMetadataScore = maxMetadataScore;

        BigDecimal earnedTestCaseScore = BigDecimal.ZERO;
        boolean testCasesPassed = true;

        if (hasTestCases) {
            testCasesPassed = gradeByTestCases(schemaName, teacherSchemaName, question, submission);
            if (submission != null && submission.getScoreEarned() != null) {
                earnedTestCaseScore = submission.getScoreEarned().multiply(testCaseWeight);
            }
        }

        BigDecimal finalScore = earnedMetadataScore.add(earnedTestCaseScore);
        if (finalScore.compareTo(totalPoints) > 0) finalScore = totalPoints;

        if (submission != null) {
            submission.setScoreEarned(finalScore);
            boolean totalPassed = allPassedMetadata && testCasesPassed;
            if (!totalPassed) {
                String tcError = submission.getErrorMessage() != null ? submission.getErrorMessage() : "";
                submission.setErrorMessage((errorBuilder.toString().trim() + " " + tcError).trim());
            }
        }

        return allPassedMetadata && testCasesPassed;
    }
}
