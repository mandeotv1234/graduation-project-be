package graduation_project_be.application.usecases;


import graduation_project_be.shared.utils.TimeUtils;
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
import java.text.Normalizer;
import java.util.*;
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
            examSchemaService.resetSchema(schemaName, false);

            log.info("Setting up teacher schema [{}] for test case validation", teacherSchemaName);
            examSchemaService.resetSchema(teacherSchemaName, false);
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
                            if (submission != null) {
                                submission.setScoreEarned(decision.scoreEarned());
                            }
                        } else {
                            boolean fallbackTriggered = false;
                            try {
                                examSchemaService.executeSql(schemaName, studentQuery);
                            } catch (Exception execErr) {
                                String compileError = execErr.getMessage();
                                if (question.getQuestionType() == QuestionType.INSERT_DATA) {
                                    boolean fkError = compileError != null && (compileError.toLowerCase().contains("foreign key")
                                            || compileError.toLowerCase().contains("ràng buộc")
                                            || compileError.toLowerCase().contains("reference")
                                            || compileError.toLowerCase().contains("conflict")
                                            || compileError.toLowerCase().contains("khóa ngoại"));
                                    if (fkError) {
                                        fallbackTriggered = true;
                                        try {
                                            setAllConstraintsEnabled(schemaName, false);
                                        } catch (Exception ignore) {}
                                        try {
                                            examSchemaService.executeSql(schemaName, studentQuery);
                                        } catch (Exception retryErr) {
                                            errorMessage = "Lỗi Execute (sau khi tắt FK): " + retryErr.getMessage();
                                            hasExecutionError = true;
                                        }
                                        try {
                                            setAllConstraintsEnabled(schemaName, true);
                                        } catch (Exception ignore) {}
                                    } else {
                                        errorMessage = "Cảnh báo Lỗi Execute: " + compileError;
                                        hasExecutionError = true;
                                    }
                                } else {
                                    errorMessage = "Cảnh báo Lỗi Execute: " + compileError;
                                    hasExecutionError = true;
                                }
                            }
                            if (hasExecutionError && isCreateTableFailAllMode(question)) {
                                if (submission != null) {
                                    submission.setScoreEarned(BigDecimal.ZERO);
                                    submission.setErrorMessage(
                                            "SQL lỗi thực thi và rubric đang để FAIL_ALL nên câu này bị 0 điểm.");
                                }
                                isCorrect = false;
                            } else if (submission != null) {
                                isCorrect = gradeAnswer(schemaName, teacherSchemaName, question, submission, fallbackTriggered);
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
                    || question.getQuestionType() == QuestionType.SELECT_QUERY
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
                    questionResultsJson, TimeUtils.now());

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

    private boolean gradeAnswer(String schemaName, String teacherSchemaName, ExamQuestion question, ExamSubmission submission, boolean fallbackTriggered) {
        QuestionType type = question.getQuestionType();
        switch (type) {
            case CREATE_TABLE:
                return gradeCreateTableAlgorithmic(schemaName, teacherSchemaName, question, submission);
            case INSERT_DATA:
                return gradeInsertDataAlgorithmic(schemaName, teacherSchemaName, question, submission, fallbackTriggered);
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
                return gradeCreateTableByRubricV2(schemaName, question, submission);
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
            JsonNode root = objectMapper.readTree(question.getGradingRubric());
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

    private boolean gradeCreateTableByRubricV2(String schemaName, ExamQuestion question, ExamSubmission submission) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(question.getGradingRubric());
        } catch (Exception e) {
            throw new RuntimeException("Invalid rubric JSON: " + e.getMessage());
        }

        List<TableMetadata> actualTables = examSchemaService.extractMetadata(schemaName);
        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        CreateTableRubricEvaluator.CreateTableRubricGradeResult result =
                CreateTableRubricEvaluator.evaluate(rubric, actualTables, totalPoints);

        if (submission != null) {
            submission.setScoreEarned(result.earnedPoints());
            submission.setErrorMessage(result.errorMessage());
        }

        return result.allPassed();
    }

    private boolean gradeInsertDataAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question, ExamSubmission submission, boolean fallbackTriggered) {
        // === Check for rubric-based grading ===
        if (question.getGradingRubric() != null && !question.getGradingRubric().isBlank()) {
            try {
                return gradeInsertDataByRubric(schemaName, question, submission, fallbackTriggered);
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

    public boolean gradeInsertDataByRubric(String schemaName, ExamQuestion question, ExamSubmission submission, boolean fallbackTriggered) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(question.getGradingRubric());
        } catch (Exception e) {
            throw new RuntimeException("Invalid rubric JSON: " + e.getMessage());
        }

        JsonNode payload = resolveInsertPayload(rubric);
        JsonNode settings = payload.path("grading_settings");
        JsonNode gradingRules = resolveInsertGradingRules(rubric, payload);
        boolean trimSpaces = readBooleanSetting(settings.path("trim_string_spaces"), true);
        boolean caseInsensitive = readBooleanSetting(settings.path("case_insensitive_data"), false);
        boolean hasLegacyExtraRowSettings = hasLegacyInsertExtraRowSettings(settings);
        boolean allowExtraRows = readBooleanSetting(settings.path("allow_extra_rows"), false);
        double penaltyPerExtraRow = Math.max(0d, readDoubleSetting(settings.path("penalty_per_extra_row"), 0.1d));
        boolean failAllMode = "FAIL_ALL".equalsIgnoreCase(settings.path("syntax_error_action").asText("PARTIAL"))
            || hasInsertFailAllRule(gradingRules);

        return gradeInsertDataByRubricDeduction(
                schemaName,
                question,
                submission,
                rubric,
                payload,
                gradingRules,
                trimSpaces,
                caseInsensitive,
                hasLegacyExtraRowSettings,
                allowExtraRows,
                penaltyPerExtraRow,
                failAllMode,
                fallbackTriggered);
    }

    private boolean gradeInsertDataByRubricDeduction(
            String schemaName,
            ExamQuestion question,
            ExamSubmission submission,
            JsonNode rubric,
            JsonNode payload,
            JsonNode gradingRules,
            boolean trimSpaces,
            boolean caseInsensitive,
            boolean hasLegacyExtraRowSettings,
            boolean allowExtraRows,
            double penaltyPerExtraRow,
            boolean failAllMode,
            boolean fallbackTriggered) {
        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        BigDecimal earnedTotal = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;
        boolean failAllTriggered = false;

        JsonNode datasets = resolveInsertDatasets(rubric, payload);
        int datasetCount = datasets.isArray() ? datasets.size() : 0;
        double defaultTablePoints = datasetCount > 0
                ? totalPoints.doubleValue() / datasetCount
                : 0d;

        JsonNode missingRowRule = findInsertRule(gradingRules, "ROW", "IS_MISSING");
        JsonNode extraRowRule = findInsertRule(gradingRules, "ROW", "IS_EXTRA");
        JsonNode cellNotEqualRule = findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
        JsonNode cellNullRule = findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
        JsonNode rowOrderRule = findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
        JsonNode fkRule = findInsertRule(gradingRules, "FOREIGN_KEY", "REFERENCE_ERROR");

        if (fallbackTriggered && fkRule != null && fkRule.isObject()) {
            InsertRuleDecision fkDecision = resolveInsertRuleDecision(fkRule, totalPoints.doubleValue(), 0d);
            if (!fkDecision.ignore()) {
                allPassed = false;
                if (fkDecision.failAll()) {
                    failAllTriggered = true;
                } else {
                    double deduction = fkDecision.penaltyPoints();
                    if (deduction > 0d) {
                        totalPoints = BigDecimal.valueOf(Math.max(0d, totalPoints.doubleValue() - deduction));
                        errorBuilder.append(String.format("Lỗi khóa ngoại (FK): vi phạm tham chiếu/ràng buộc, hệ thống tự động chạy lại (trừ %.2f điểm). ", deduction));
                    }
                }
            }
        }

        JsonNode rowMatchModifiers = firstNonEmptyModifiers(
                extractInsertRuleModifiers(missingRowRule),
                extractInsertRuleModifiers(cellNotEqualRule));
        JsonNode cellCompareModifiers = extractInsertRuleModifiers(cellNotEqualRule);
        JsonNode cellNullModifiers = firstNonEmptyModifiers(
                extractInsertRuleModifiers(cellNullRule),
                cellCompareModifiers);

        for (int i = 0; i < datasets.size(); i++) {
            JsonNode dataset = datasets.get(i);
            String tableName = dataset.path("table_name").asText("").trim();
            if (tableName.isBlank()) {
                continue;
            }

            double tablePoints = Math.max(0d, dataset.path("table_points").asDouble(0d));
            if (tablePoints <= 0d) {
                tablePoints = Math.max(0d, defaultTablePoints);
            }

            JsonNode expectedRows = dataset.path("expected_data");
            if (expectedRows == null || expectedRows.isMissingNode() || !expectedRows.isArray()) {
                expectedRows = dataset.path("rows");
            }
            if (expectedRows == null || expectedRows.isMissingNode() || !expectedRows.isArray() || expectedRows.size() == 0) {
                earnedTotal = earnedTotal.add(BigDecimal.valueOf(tablePoints));
                continue;
            }

            double rowPenalty = dataset.path("missing_row_penalty").asDouble(0d);
            if (rowPenalty <= 0d) {
                rowPenalty = dataset.path("points_per_row").asDouble(0d);
            }
            if (rowPenalty <= 0d) {
                Double rowRulePenalty = resolveInsertRulePenaltyPoints(
                        gradingRules,
                        "ROW",
                        "IS_MISSING",
                        tablePoints);
                if (rowRulePenalty != null && rowRulePenalty > 0d) {
                    rowPenalty = rowRulePenalty;
                }
            }
            if (rowPenalty <= 0d) {
                rowPenalty = tablePoints / Math.max(1, expectedRows.size());
            }
            rowPenalty = Math.max(0d, rowPenalty);

            boolean allOrNothing = "ALL_OR_NOTHING".equalsIgnoreCase(
                    dataset.path("row_grading_strategy").asText("PARTIAL_BY_COLUMN"));

            List<String> columnsToGrade = new ArrayList<>();
            List<String> primaryKeys = new ArrayList<>();
            Map<String, Double> columnPenalties = new HashMap<>();
            Map<String, String> columnMatchTypes = new HashMap<>();

            JsonNode columnsConfig = dataset.path("columns_config");
            if (columnsConfig.isArray()) {
                for (int c = 0; c < columnsConfig.size(); c++) {
                    JsonNode cc = columnsConfig.get(c);
                    String columnName = cc.path("name").asText("").trim();
                    if (columnName.isBlank()) {
                        continue;
                    }

                    if (cc.path("is_graded").asBoolean(true)) {
                        columnsToGrade.add(columnName);
                        columnPenalties.put(columnName, Math.max(0d, cc.path("points").asDouble(0d)));
                        columnMatchTypes.put(columnName, cc.path("match_type").asText("EXACT"));
                    }

                    if (cc.path("is_primary_key").asBoolean(false)) {
                        primaryKeys.add(columnName);
                    }
                }
            }

            if (primaryKeys.isEmpty()) {
                JsonNode pksNode = dataset.path("primary_keys");
                if (pksNode.isArray()) {
                    for (int p = 0; p < pksNode.size(); p++) {
                        String pk = pksNode.get(p).asText("").trim();
                        if (!pk.isBlank()) {
                            primaryKeys.add(pk);
                        }
                    }
                }
            }

            if (columnsToGrade.isEmpty()) {
                JsonNode columnsToGradeNode = dataset.path("columns_to_grade");
                if (columnsToGradeNode.isArray()) {
                    for (int c = 0; c < columnsToGradeNode.size(); c++) {
                        String col = columnsToGradeNode.get(c).asText("").trim();
                        if (!col.isBlank()) {
                            columnsToGrade.add(col);
                        }
                    }
                }
            }

            if (columnsToGrade.isEmpty() && expectedRows.size() > 0) {
                expectedRows.get(0).fieldNames().forEachRemaining(columnsToGrade::add);
            }

            if (columnsToGrade.isEmpty()) {
                earnedTotal = earnedTotal.add(BigDecimal.valueOf(tablePoints));
                continue;
            }

            double fallbackColPenalty = rowPenalty / Math.max(1, columnsToGrade.size());
            Double cellRulePenalty = resolveInsertRulePenaltyPoints(
                    gradingRules,
                    "CELL_VALUE",
                    "NOT_EQUAL",
                    tablePoints);
            if (cellRulePenalty != null && cellRulePenalty > 0d) {
                fallbackColPenalty = cellRulePenalty;
            }

            for (String col : columnsToGrade) {
                columnPenalties.putIfAbsent(col, fallbackColPenalty);
                columnMatchTypes.putIfAbsent(col, "EXACT");
            }

            List<Map<String, Object>> actualRows;
            try {
                actualRows = examSchemaService.executeAdminSql("SELECT * FROM [" + schemaName + "]." + tableName).getResultSet();
            } catch (Exception e) {
                allPassed = false;
                errorBuilder.append(String.format("Bang %s bi loi hoac khong ton tai. ", tableName));
                continue;
            }

            double earnedTable = tablePoints;
            boolean[] usedActualRows = new boolean[actualRows.size()];
            List<Integer> matchedActualIndexes = new ArrayList<>();

            int missingRows = 0;
            int wrongCells = 0;
            int outOfOrderRows = 0;

            for (int r = 0; r < expectedRows.size(); r++) {
                JsonNode expectedRow = expectedRows.get(r);
                Map<String, Object> actualRow = null;
                int actualRowIdx = -1;

                for (int idx = 0; idx < actualRows.size(); idx++) {
                    if (usedActualRows[idx]) {
                        continue;
                    }

                    Map<String, Object> candidate = actualRows.get(idx);
                    boolean rowMatched;

                    if (!primaryKeys.isEmpty()) {
                        rowMatched = true;
                        for (String pk : primaryKeys) {
                            if (!valuesEqualByMatchTypeWithModifiers(
                                    getRowValueIgnoreCase(candidate, pk),
                                    getExpectedValueAsText(expectedRow, pk),
                                    "EXACT",
                                    rowMatchModifiers,
                                    trimSpaces,
                                    caseInsensitive)) {
                                rowMatched = false;
                                break;
                            }
                        }
                    } else {
                        rowMatched = false;
                        for (String col : columnsToGrade) {
                            String matchType = columnMatchTypes.getOrDefault(col, "EXACT");
                            if (valuesEqualByMatchTypeWithModifiers(
                                    getRowValueIgnoreCase(candidate, col),
                                    getExpectedValueAsText(expectedRow, col),
                                    matchType,
                                    rowMatchModifiers,
                                    trimSpaces,
                                    caseInsensitive)) {
                                rowMatched = true;
                                break;
                            }
                        }
                    }

                    if (rowMatched) {
                        actualRow = candidate;
                        actualRowIdx = idx;
                        break;
                    }
                }

                if (actualRow == null) {
                    InsertRuleDecision missingDecision = resolveInsertRuleDecision(missingRowRule, tablePoints, rowPenalty);
                    if (!missingDecision.ignore()) {
                        allPassed = false;
                        missingRows++;
                        if (missingDecision.failAll()) {
                            failAllTriggered = true;
                            earnedTable = 0d;
                            break;
                        }
                        earnedTable = applyInsertPenalty(earnedTable, missingDecision.penaltyPoints(), 1);
                    }
                    continue;
                }

                usedActualRows[actualRowIdx] = true;
                matchedActualIndexes.add(actualRowIdx);

                double rowDeduction = 0d;
                boolean rowHasDeduction = false;
                boolean rowFailAll = false;

                for (String col : columnsToGrade) {
                    Object actualRawValue = getRowValueIgnoreCase(actualRow, col);
                    String expectedRawValue = getExpectedValueAsText(expectedRow, col);
                    String matchType = columnMatchTypes.getOrDefault(col, "EXACT");

                    boolean nullViolation = false;
                    if (cellNullRule != null) {
                        String normalizedActualForNull = applyInsertModifiers(
                                normalizeValueStr(actualRawValue, trimSpaces, caseInsensitive),
                                cellNullModifiers);
                        String normalizedExpectedForNull = applyInsertModifiers(
                                normalizeValueStr(expectedRawValue, trimSpaces, caseInsensitive),
                                cellNullModifiers);
                        nullViolation = !isNullLike(normalizedExpectedForNull) && isNullLike(normalizedActualForNull);
                    }

                    boolean isEqual = valuesEqualByMatchTypeWithModifiers(
                            actualRawValue,
                            expectedRawValue,
                            matchType,
                            cellCompareModifiers,
                            trimSpaces,
                            caseInsensitive);

                    if (!nullViolation && isEqual) {
                        continue;
                    }

                    JsonNode activeRule = nullViolation && cellNullRule != null
                            ? cellNullRule
                            : cellNotEqualRule;
                    double defaultPenalty = columnPenalties.getOrDefault(col, fallbackColPenalty);
                    InsertRuleDecision cellDecision = resolveInsertRuleDecision(activeRule, tablePoints, defaultPenalty);

                    if (cellDecision.ignore()) {
                        continue;
                    }

                    allPassed = false;
                    wrongCells++;

                    if (cellDecision.failAll()) {
                        rowFailAll = true;
                        break;
                    }

                    rowHasDeduction = true;
                    if (allOrNothing) {
                        rowDeduction = Math.max(rowDeduction, Math.max(rowPenalty, cellDecision.penaltyPoints()));
                    } else {
                        rowDeduction += cellDecision.penaltyPoints();
                    }
                }

                if (rowFailAll) {
                    failAllTriggered = true;
                    earnedTable = 0d;
                    break;
                }

                if (rowHasDeduction && rowDeduction > 0d) {
                    earnedTable = applyInsertPenalty(earnedTable, rowDeduction, 1);
                }
            }

            if (earnedTable > 0d && rowOrderRule != null && !hasInsertModifier(rowOrderRule, "SORT_ASC")) {
                outOfOrderRows = countInsertOutOfOrderViolations(matchedActualIndexes);
                if (outOfOrderRows > 0) {
                    InsertRuleDecision rowOrderDecision = resolveInsertRuleDecision(rowOrderRule, tablePoints, rowPenalty);
                    if (!rowOrderDecision.ignore()) {
                        allPassed = false;
                        if (rowOrderDecision.failAll()) {
                            failAllTriggered = true;
                            earnedTable = 0d;
                        } else {
                            earnedTable = applyInsertPenalty(earnedTable, rowOrderDecision.penaltyPoints(), outOfOrderRows);
                        }
                    }
                }
            }

            int extraRows = 0;
            for (boolean used : usedActualRows) {
                if (!used) {
                    extraRows++;
                }
            }

            if (earnedTable > 0d && extraRows > 0) {
                if (!hasLegacyExtraRowSettings && extraRowRule != null && extraRowRule.isObject()) {
                    double defaultExtraPenalty = Math.max(rowPenalty, tablePoints * penaltyPerExtraRow);
                    InsertRuleDecision extraDecision = resolveInsertRuleDecision(extraRowRule, tablePoints, defaultExtraPenalty);

                    if (!extraDecision.ignore()) {
                        allPassed = false;
                        if (extraDecision.failAll()) {
                            failAllTriggered = true;
                            earnedTable = 0d;
                        } else {
                            earnedTable = applyInsertPenalty(earnedTable, extraDecision.penaltyPoints(), extraRows);
                        }
                    }
                } else {
                    allPassed = false;
                    if (allowExtraRows) {
                        double penalty = tablePoints * penaltyPerExtraRow * extraRows;
                        earnedTable = applyInsertPenalty(earnedTable, penalty, 1);
                    } else {
                        earnedTable = 0d;
                    }
                }
            }

            if (missingRows > 0 || wrongCells > 0 || extraRows > 0 || outOfOrderRows > 0) {
                double tableDeduction = Math.max(0d, tablePoints - Math.max(0d, earnedTable));
                errorBuilder.append(String.format(
                        Locale.ROOT,
                        "Bang %s: thieu %d dong, sai %d o, du %d dong, sai thu tu %d dong, tru %.2f diem. ",
                        tableName,
                        missingRows,
                        wrongCells,
                        extraRows,
                        outOfOrderRows,
                        tableDeduction));
            }

            earnedTotal = earnedTotal.add(BigDecimal.valueOf(Math.max(0d, earnedTable)));
        }

        earnedTotal = earnedTotal.setScale(2, RoundingMode.HALF_UP);
        if ((failAllMode && !allPassed) || failAllTriggered) {
            earnedTotal = BigDecimal.ZERO;
            if (errorBuilder.length() > 0) {
                errorBuilder.insert(0, "Rubric dang de FAIL_ALL: co loi nen cau nay bi 0 diem toan bo. ");
            } else {
                errorBuilder.append("Rubric dang de FAIL_ALL: co loi nen cau nay bi 0 diem toan bo.");
            }
        }

        if (earnedTotal.compareTo(totalPoints) > 0) earnedTotal = totalPoints;
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0) earnedTotal = BigDecimal.ZERO;

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed || failAllTriggered) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        return allPassed && !failAllTriggered;
    }

    private record InsertRuleDecision(String action, double penaltyPoints, boolean ignore, boolean failAll) {
    }

    private InsertRuleDecision resolveInsertRuleDecision(JsonNode ruleNode, double tablePoints, double defaultPenaltyPoints) {
        String action = ruleNode != null ? ruleNode.path("action").asText("").trim() : "";
        if (action.isBlank()) {
            action = "DEDUCT_POINTS";
        }

        String normalizedAction = action.toUpperCase(Locale.ROOT);
        double safeDefaultPenalty = Math.max(0d, defaultPenaltyPoints);
        double penaltyValue = ruleNode != null
                ? readDoubleSetting(ruleNode.path("penalty_value"), -1d)
                : -1d;

        switch (normalizedAction) {
            case "IGNORE":
                return new InsertRuleDecision(normalizedAction, 0d, true, false);
            case "FAIL_ALL":
                return new InsertRuleDecision(normalizedAction, Math.max(0d, tablePoints), false, true);
            case "FAIL_ITEM":
                return new InsertRuleDecision(normalizedAction, safeDefaultPenalty, false, false);
            case "DEDUCT_PERCENTAGE": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, tablePoints * penaltyValue / 100d)
                        : safeDefaultPenalty;
                return new InsertRuleDecision(normalizedAction, penalty, false, false);
            }
            case "DEDUCT_POINTS": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, penaltyValue)
                        : safeDefaultPenalty;
                return new InsertRuleDecision(normalizedAction, penalty, false, false);
            }
            default:
                return new InsertRuleDecision("DEDUCT_POINTS", safeDefaultPenalty, false, false);
        }
    }

    private double applyInsertPenalty(double earnedTable, double penaltyPoints, int violationCount) {
        if (violationCount <= 0 || penaltyPoints <= 0d) {
            return earnedTable;
        }
        return Math.max(0d, earnedTable - (penaltyPoints * violationCount));
    }

    private JsonNode extractInsertRuleModifiers(JsonNode ruleNode) {
        if (ruleNode == null || !ruleNode.isObject()) {
            return objectMapper.createArrayNode();
        }

        JsonNode modifiers = ruleNode.path("modifiers");
        return modifiers.isArray() ? modifiers : objectMapper.createArrayNode();
    }

    private JsonNode firstNonEmptyModifiers(JsonNode primary, JsonNode fallback) {
        if (primary != null && primary.isArray() && primary.size() > 0) {
            return primary;
        }
        if (fallback != null && fallback.isArray() && fallback.size() > 0) {
            return fallback;
        }
        return objectMapper.createArrayNode();
    }

    private boolean hasInsertModifier(JsonNode ruleNode, String expectedModifier) {
        if (expectedModifier == null || expectedModifier.isBlank()) {
            return false;
        }

        JsonNode modifiers = extractInsertRuleModifiers(ruleNode);
        for (JsonNode modifierNode : modifiers) {
            if (expectedModifier.equalsIgnoreCase(modifierNode.asText(""))) {
                return true;
            }
        }

        return false;
    }

    private boolean valuesEqualByMatchTypeWithModifiers(
            Object actualValue,
            Object expectedValue,
            String matchType,
            JsonNode modifiers,
            boolean trimSpaces,
            boolean caseInsensitive) {
        String normalizedActual = normalizeValueStr(actualValue, trimSpaces, caseInsensitive);
        String normalizedExpected = normalizeValueStr(expectedValue, trimSpaces, caseInsensitive);

        String modifiedActual = applyInsertModifiers(normalizedActual, modifiers);
        String modifiedExpected = applyInsertModifiers(normalizedExpected, modifiers);

        return valuesEqualByMatchType(modifiedActual, modifiedExpected, matchType);
    }

    private String applyInsertModifiers(String value, JsonNode modifiers) {
        if (value == null) {
            return null;
        }

        if (modifiers == null || !modifiers.isArray() || modifiers.isEmpty()) {
            return value;
        }

        String current = value;
        for (JsonNode modifierNode : modifiers) {
            String modifier = modifierNode.asText("").trim().toUpperCase(Locale.ROOT);
            if (modifier.isBlank()) {
                continue;
            }

            switch (modifier) {
                case "TO_LOWERCASE":
                    current = current.toLowerCase(Locale.ROOT);
                    break;
                case "TRIM_WHITESPACE":
                    current = current.trim();
                    break;
                case "REMOVE_ALL_WHITESPACE":
                    current = current.replaceAll("\\s+", "");
                    break;
                case "REMOVE_DIACRITICS": {
                    String normalized = Normalizer.normalize(current, Normalizer.Form.NFD);
                    current = normalized.replaceAll("\\p{M}+", "");
                    break;
                }
                case "REMOVE_SPECIAL_CHARS":
                    current = current.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", "");
                    break;
                case "CAST_TO_STRING":
                    current = String.valueOf(current);
                    break;
                case "CAST_TO_FLOAT":
                    current = canonicalizeNumber(current, -1);
                    break;
                case "ROUND_TO_INT":
                    current = canonicalizeNumber(current, 0);
                    break;
                case "ROUND_2_DECIMALS":
                    current = canonicalizeNumber(current, 2);
                    break;
                case "SORT_ASC":
                    current = sortTokensAscending(current);
                    break;
                default:
                    break;
            }
        }

        return current;
    }

    private String sortTokensAscending(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return trimmed;
        }

        boolean commaSeparated = trimmed.contains(",");
        String[] rawTokens = commaSeparated
                ? trimmed.split("\\s*,\\s*")
                : trimmed.split("\\s+");
        if (rawTokens.length <= 1) {
            return trimmed;
        }

        List<String> tokens = new ArrayList<>();
        for (String rawToken : rawTokens) {
            if (rawToken == null) {
                continue;
            }
            String token = rawToken.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }

        if (tokens.size() <= 1) {
            return trimmed;
        }

        tokens.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(commaSeparated ? "," : " ", tokens);
    }

    private String canonicalizeNumber(String value, int targetScale) {
        if (value == null) {
            return null;
        }

        BigDecimal decimal = parseDecimal(value.trim());
        if (decimal == null) {
            return value;
        }

        if (targetScale >= 0) {
            decimal = decimal.setScale(targetScale, RoundingMode.HALF_UP);
        }

        return decimal.stripTrailingZeros().toPlainString();
    }

    private boolean isNullLike(String val) {
        return val == null || val.isEmpty() || "null".equalsIgnoreCase(val);
    }

    private int countInsertOutOfOrderViolations(List<Integer> matchedActualIndexes) {
        if (matchedActualIndexes == null || matchedActualIndexes.size() <= 1) {
            return 0;
        }

        int maxSeen = -1;
        int violations = 0;
        for (Integer actualIdx : matchedActualIndexes) {
            if (actualIdx == null || actualIdx < 0) {
                continue;
            }

            if (actualIdx < maxSeen) {
                violations++;
            } else {
                maxSeen = actualIdx;
            }
        }

        return violations;
    }

    private String normalizeValueStr(Object val, boolean trimSpaces, boolean caseInsensitive) {
        if (val == null) return null;
        String s = String.valueOf(val);
        if (trimSpaces) s = s.trim();
        if (caseInsensitive) s = s.toLowerCase();
        return s;
    }

    private Object getRowValueIgnoreCase(Map<String, Object> row, String columnName) {
        if (row == null || columnName == null) {
            return null;
        }

        if (row.containsKey(columnName)) {
            return row.get(columnName);
        }

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private String getExpectedValueAsText(JsonNode expectedRow, String columnName) {
        if (expectedRow == null || columnName == null || !expectedRow.isObject()) {
            return null;
        }

        JsonNode direct = expectedRow.get(columnName);
        if (direct != null) {
            return direct.isNull() ? null : direct.asText();
        }

        Iterator<String> fields = expectedRow.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (field != null && field.equalsIgnoreCase(columnName)) {
                JsonNode value = expectedRow.get(field);
                return value == null || value.isNull() ? null : value.asText();
            }
        }

        return null;
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

    private boolean valuesEqualByMatchType(String actual, String expected, String matchType) {
        String normalizedMatchType = matchType == null ? "EXACT" : matchType.trim().toUpperCase(Locale.ROOT);
        switch (normalizedMatchType) {
            case "IGNORE_CASE_AND_SPACE":
                String actualIgnoreCase = normalizeValueStr(actual, true, true);
                String expectedIgnoreCase = normalizeValueStr(expected, true, true);
                return valuesEqual(actualIgnoreCase, expectedIgnoreCase);
            case "NUMERIC_TOLERANCE":
                BigDecimal aNum = parseDecimal(actual);
                BigDecimal eNum = parseDecimal(expected);
                if (aNum != null && eNum != null) {
                    BigDecimal diff = aNum.subtract(eNum).abs();
                    return diff.compareTo(new BigDecimal("0.000001")) <= 0;
                }
                return valuesEqual(actual, expected);
            case "EXACT":
            default:
                return valuesEqual(actual, expected);
        }
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

    private JsonNode resolveInsertPayload(JsonNode rubric) {
        if (rubric == null || rubric.isMissingNode() || rubric.isNull()) {
            return objectMapper.createObjectNode();
        }

        JsonNode payload = rubric.path("grading_payload");
        if (payload != null && payload.isObject()) {
            return payload;
        }

        return rubric;
    }

    private JsonNode resolveInsertDatasets(JsonNode rubric, JsonNode payload) {
        JsonNode[] candidates = new JsonNode[] {
                payload.path("tables"),
                payload.path("expected_datasets"),
                rubric.path("tables"),
                rubric.path("expected_datasets")
        };

        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isArray() && candidate.size() > 0) {
                return candidate;
            }
        }

        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }

        return objectMapper.createArrayNode();
    }

    private JsonNode resolveInsertGradingRules(JsonNode rubric, JsonNode payload) {
        JsonNode[] candidates = new JsonNode[] {
                payload.path("grading_rules"),
                rubric.path("grading_rules")
        };

        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }

        return objectMapper.createArrayNode();
    }

    private boolean hasLegacyInsertExtraRowSettings(JsonNode settings) {
        if (settings == null || settings.isMissingNode() || settings.isNull()) {
            return false;
        }
        return settings.path("allow_extra_rows").isValueNode()
                || settings.path("penalty_per_extra_row").isValueNode();
    }

    private boolean hasInsertFailAllRule(JsonNode gradingRules) {
        if (!gradingRules.isArray()) {
            return false;
        }

        for (JsonNode ruleNode : gradingRules) {
            if (!ruleNode.isObject()) {
                continue;
            }
            if ("FAIL_ALL".equalsIgnoreCase(ruleNode.path("action").asText(""))) {
                return true;
            }
        }

        return false;
    }

    private Double resolveInsertRulePenaltyPoints(
            JsonNode gradingRules,
            String target,
            String condition,
            double tablePoints) {
        JsonNode ruleNode = findInsertRule(gradingRules, target, condition);
        if (ruleNode == null) {
            return null;
        }

        String action = ruleNode.path("action").asText("").trim();
        if (action.isBlank()) {
            return null;
        }

        double penaltyValue = Math.max(0d, readDoubleSetting(ruleNode.path("penalty_value"), 0d));
        if ("DEDUCT_POINTS".equalsIgnoreCase(action)) {
            return penaltyValue;
        }

        if ("DEDUCT_PERCENTAGE".equalsIgnoreCase(action)) {
            return Math.max(0d, tablePoints * penaltyValue / 100d);
        }

        return null;
    }

    private JsonNode findInsertRule(JsonNode gradingRules, String target, String condition) {
        if (!gradingRules.isArray()) {
            return null;
        }

        for (JsonNode ruleNode : gradingRules) {
            if (!ruleNode.isObject()) {
                continue;
            }

            String ruleTarget = ruleNode.path("target").asText("").trim();
            String ruleCondition = ruleNode.path("condition").asText("").trim();
            if (target.equalsIgnoreCase(ruleTarget) && condition.equalsIgnoreCase(ruleCondition)) {
                return ruleNode;
            }
        }

        return null;
    }

    private GradeDecision gradeSelectAcrossDatasets(
            ExamSpecification specification,
            String schemaName,
            ExamQuestion question,
            String studentQuery) {
        if (question.getCorrectQuery() == null || question.getCorrectQuery().isBlank()) {
            return GradeDecision.fail("Missing correctQuery for SELECT question");
        }
        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;

        // Fallback: no specification → grade directly on current schema state
        if (specification == null
                || specification.getDdlScript() == null
                || specification.getDdlScript().isBlank()) {
            return gradeSelectDirectOnCurrentSchema(schemaName, question, studentQuery, totalPoints);
        }

        List<SpecDataset> activeDatasets = specification.getDatasets() == null ? List.of()
                : specification.getDatasets().stream()
                        .filter(SpecDataset::isActive)
                        .sorted(Comparator.comparingInt(SpecDataset::getOrderIndex))
                        .toList();

        JsonNode selectRules = resolveSelectGradingRules(question);
        boolean hasRuleBasedScoring = hasSelectGradingRules(selectRules);

        List<SelectDatasetSpec> datasetsToGrade = new ArrayList<>();
        if (activeDatasets.isEmpty()) {
            datasetsToGrade.add(new SelectDatasetSpec("fallback-no-dataset", null));
        } else {
            for (SpecDataset dataset : activeDatasets) {
                String datasetLabel = "dataset[" + dataset.getId() + ":" + dataset.getName() + "]";
                datasetsToGrade.add(new SelectDatasetSpec(datasetLabel, dataset.getDataScript()));
            }
        }

        if (!hasRuleBasedScoring) {
            for (SelectDatasetSpec datasetSpec : datasetsToGrade) {
                SelectDatasetDecision decision = gradeSelectWithSingleDatasetStrict(
                        schemaName,
                        specification.getDdlScript(),
                        datasetSpec.datasetScript(),
                        datasetSpec.datasetLabel(),
                        question,
                        studentQuery);
                if (!decision.allChecksPassed()) {
                    return GradeDecision.fail(decision.errorMessage());
                }
            }
            return GradeDecision.pass(totalPoints);
        }

        BigDecimal earnedTotal = BigDecimal.ZERO;
        BigDecimal distributedPoints = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;
        boolean failAllTriggered = false;

        BigDecimal baseDatasetPoints = totalPoints;
        if (!datasetsToGrade.isEmpty()) {
            baseDatasetPoints = totalPoints.divide(
                    BigDecimal.valueOf(datasetsToGrade.size()),
                    8,
                    RoundingMode.HALF_UP);
        }

        for (int i = 0; i < datasetsToGrade.size(); i++) {
            SelectDatasetSpec datasetSpec = datasetsToGrade.get(i);
            BigDecimal datasetMaxPoints = (i == datasetsToGrade.size() - 1)
                    ? totalPoints.subtract(distributedPoints)
                    : baseDatasetPoints;
            if (datasetMaxPoints.compareTo(BigDecimal.ZERO) < 0) {
                datasetMaxPoints = BigDecimal.ZERO;
            }
            distributedPoints = distributedPoints.add(datasetMaxPoints);

            SelectDatasetDecision decision = gradeSelectWithSingleDatasetByRules(
                    schemaName,
                    specification.getDdlScript(),
                    datasetSpec.datasetScript(),
                    datasetSpec.datasetLabel(),
                    question,
                    studentQuery,
                    selectRules,
                    datasetMaxPoints);

            if (decision.executionFailed()) {
                return GradeDecision.fail(decision.errorMessage());
            }

            earnedTotal = earnedTotal.add(decision.earnedPoints());
            if (!decision.allChecksPassed()) {
                allPassed = false;
            }
            if (decision.failAllTriggered()) {
                failAllTriggered = true;
            }
            if (decision.errorMessage() != null && !decision.errorMessage().isBlank()) {
                appendSelectIssue(errorBuilder, decision.errorMessage());
            }
        }

        if (failAllTriggered) {
            allPassed = false;
            earnedTotal = BigDecimal.ZERO;
            appendSelectIssue(errorBuilder,
                    "Rubric SELECT co rule FAIL_ALL: cau nay bi 0 diem toan bo.");
        }

        if (earnedTotal.compareTo(totalPoints) > 0) {
            earnedTotal = totalPoints;
        }
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0) {
            earnedTotal = BigDecimal.ZERO;
        }
        earnedTotal = earnedTotal.setScale(2, RoundingMode.HALF_UP);

        if (allPassed && earnedTotal.compareTo(totalPoints.setScale(2, RoundingMode.HALF_UP)) >= 0) {
            return GradeDecision.pass(earnedTotal);
        }

        String errorMessage = errorBuilder.length() > 0
                ? errorBuilder.toString().trim()
                : "Kết quả SELECT không khớp rubric chấm điểm.";
        return GradeDecision.partial(earnedTotal, errorMessage);
    }

    /**
     * Fallback grading for SELECT questions when no ExamSpecification is
     * attached to the exam.  Runs both the student query and the correct query
     * on the schema as-is (no DDL reload, no multi-dataset loop) and compares
     * the results.
     */
    private GradeDecision gradeSelectDirectOnCurrentSchema(
            String schemaName,
            ExamQuestion question,
            String studentQuery,
            BigDecimal totalPoints) {
        try {
            List<Map<String, Object>> actual =
                    examSchemaService.executeSql(schemaName, studentQuery).getResultSet();
            List<Map<String, Object>> expected =
                    examSchemaService.executeSql(schemaName, question.getCorrectQuery()).getResultSet();

            boolean requireStrictOrder = question.getCorrectQuery() != null
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            // --- rule-based grading (if rules exist) ---
            JsonNode selectRules = resolveSelectGradingRules(question);
            boolean hasRuleBasedScoring = hasSelectGradingRules(selectRules);

            if (!hasRuleBasedScoring) {
                // Simple strict comparison
                if (compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                    return GradeDecision.pass(totalPoints);
                }
                return GradeDecision.fail("Kết quả SELECT không khớp với đáp án.");
            }

            // Exact match → full points immediately
            if (compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                return GradeDecision.pass(totalPoints);
            }

            // Delegate to rule-based scoring with a single "virtual" dataset
            SelectDatasetDecision decision = gradeSelectWithSingleDatasetByRulesOnCurrentResults(
                    actual, expected, question, studentQuery, selectRules, totalPoints, requireStrictOrder);

            if (decision.executionFailed()) {
                return GradeDecision.fail(decision.errorMessage());
            }
            if (decision.failAllTriggered()) {
                return GradeDecision.fail(
                        "Rubric SELECT có rule FAIL_ALL: câu này bị 0 điểm toàn bộ.");
            }

            BigDecimal earned = decision.earnedPoints();
            if (earned.compareTo(totalPoints) > 0) earned = totalPoints;
            if (earned.compareTo(BigDecimal.ZERO) < 0) earned = BigDecimal.ZERO;
            earned = earned.setScale(2, RoundingMode.HALF_UP);

            if (decision.allChecksPassed()
                    && earned.compareTo(totalPoints.setScale(2, RoundingMode.HALF_UP)) >= 0) {
                return GradeDecision.pass(earned);
            }

            String errorMessage = decision.errorMessage() != null && !decision.errorMessage().isBlank()
                    ? decision.errorMessage()
                    : "Kết quả SELECT không khớp rubric chấm điểm.";
            return GradeDecision.partial(earned, errorMessage);
        } catch (Exception e) {
            return GradeDecision.fail("Lỗi khi chấm SELECT: " + e.getMessage());
        }
    }

    /**
     * Rule-based scoring on pre-computed result sets (no schema manipulation).
     */
    private SelectDatasetDecision gradeSelectWithSingleDatasetByRulesOnCurrentResults(
            List<Map<String, Object>> actual,
            List<Map<String, Object>> expected,
            ExamQuestion question,
            String studentQuery,
            JsonNode gradingRules,
            BigDecimal datasetMaxPoints,
            boolean requireStrictOrder) {
        try {
            List<String> expectedColumns = extractSelectColumns(expected);
            List<String> actualColumns = extractSelectColumns(actual);
            List<String> comparisonColumns = !expectedColumns.isEmpty() ? expectedColumns : actualColumns;

            List<Map<String, Object>> remappedActual = remapActualRowsByPosition(
                    actual, actualColumns, expectedColumns);

            int expectedRowsCount = expected == null ? 0 : expected.size();
            int actualRowsCount = actual == null ? 0 : actual.size();
            int missingRows = Math.max(0, expectedRowsCount - actualRowsCount);
            int extraRows = Math.max(0, actualRowsCount - expectedRowsCount);

            int nameMismatchAtSamePosition = 0;
            int minCols = Math.min(expectedColumns.size(), actualColumns.size());
            for (int i = 0; i < minCols; i++) {
                if (!expectedColumns.get(i).equalsIgnoreCase(actualColumns.get(i))) {
                    nameMismatchAtSamePosition++;
                }
            }
            int trulyMissingColumns = Math.max(0, expectedColumns.size() - actualColumns.size());
            int trulyExtraColumns = Math.max(0, actualColumns.size() - expectedColumns.size());
            int missingColumns = nameMismatchAtSamePosition + trulyMissingColumns;
            int extraColumns = trulyExtraColumns;

            int columnOrderViolations = 0;
            if (nameMismatchAtSamePosition == 0 && trulyMissingColumns == 0 && trulyExtraColumns == 0
                    && !expectedColumns.isEmpty() && !actualColumns.isEmpty()
                    && !sameColumnOrderIgnoreCase(expectedColumns, actualColumns)) {
                columnOrderViolations = 1;
            }

            JsonNode rowOrderRule = findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
            int rowOrderViolations = 0;
            if (requireStrictOrder && rowOrderRule != null && !hasInsertModifier(rowOrderRule, "SORT_ASC")) {
                rowOrderViolations = countSelectRowOrderViolations(remappedActual, expected, comparisonColumns);
                if (rowOrderViolations == 0) {
                    rowOrderViolations = 1;
                }
            }

            JsonNode cellNotEqualRule = findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
            JsonNode cellNullRule = findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
            JsonNode cellCompareModifiers = firstNonEmptyModifiers(
                    extractInsertRuleModifiers(cellNotEqualRule),
                    extractInsertRuleModifiers(cellNullRule));

            List<SelectRowPair> rowPairs = buildSelectRowPairs(
                    remappedActual, expected, comparisonColumns, requireStrictOrder, cellCompareModifiers);
            int wrongCells = countSelectCellMismatches(rowPairs, comparisonColumns, cellCompareModifiers);
            int nullViolations = countSelectNullViolations(rowPairs, comparisonColumns, cellCompareModifiers);

            double datasetPoints = datasetMaxPoints.doubleValue();
            int expectedColumnsCount = Math.max(1, comparisonColumns.size());
            int expectedRowsForPenalty = Math.max(1, expectedRowsCount);
            double rowPenaltyDefault = datasetPoints / expectedRowsForPenalty;
            double columnPenaltyDefault = datasetPoints / expectedColumnsCount;
            double cellPenaltyDefault = rowPenaltyDefault / expectedColumnsCount;

            List<SelectRuleApplication> applications = List.of(
                    applySelectRule(gradingRules, "ROW", "IS_MISSING", missingRows,
                            datasetMaxPoints, rowPenaltyDefault, "thieu " + missingRows + " dong"),
                    applySelectRule(gradingRules, "ROW", "IS_EXTRA", extraRows,
                            datasetMaxPoints, rowPenaltyDefault, "du " + extraRows + " dong"),
                    applySelectRule(gradingRules, "CELL_VALUE", "NOT_EQUAL", wrongCells,
                            datasetMaxPoints, cellPenaltyDefault, "sai " + wrongCells + " o du lieu"),
                    applySelectRule(gradingRules, "CELL_VALUE", "IS_NULL", nullViolations,
                            datasetMaxPoints, cellPenaltyDefault, "co " + nullViolations + " o gia tri rong"),
                    applySelectRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER", rowOrderViolations,
                            datasetMaxPoints, rowPenaltyDefault, "sai thu tu " + rowOrderViolations + " dong"),
                    applySelectRule(gradingRules, "COLUMN_ORDER", "OUT_OF_ORDER", columnOrderViolations,
                            datasetMaxPoints, columnPenaltyDefault, "sai thu tu cot ket qua"),
                    applySelectRule(gradingRules, "COLUMN", "IS_MISSING", missingColumns,
                            datasetMaxPoints, columnPenaltyDefault, "thieu " + missingColumns + " cot"),
                    applySelectRule(gradingRules, "COLUMN", "IS_EXTRA", extraColumns,
                            datasetMaxPoints, columnPenaltyDefault, "du " + extraColumns + " cot"));

            double earned = datasetPoints;
            int matchedRuleCount = 0;
            boolean failAllTriggered = false;
            StringBuilder issueBuilder = new StringBuilder();

            for (SelectRuleApplication application : applications) {
                if (!application.violationPresent()) continue;
                if (application.ruleMatched()) matchedRuleCount++;
                if (application.failAllTriggered()) failAllTriggered = true;
                if (application.deduction().compareTo(BigDecimal.ZERO) > 0) {
                    earned -= application.deduction().doubleValue();
                }
                if (application.message() != null && !application.message().isBlank()) {
                    appendSelectIssue(issueBuilder, application.message());
                }
            }

            if (failAllTriggered) {
                earned = 0d;
            } else if (matchedRuleCount == 0) {
                // Violations exist but no rules matched -> don't deduct points
                appendSelectIssue(issueBuilder, "Phát hiện sai lệch nhưng không có quy tắc chấm phù hợp -> không trừ điểm.");
            }

            if (earned < 0d) earned = 0d;
            if (earned > datasetPoints) earned = datasetPoints;

            BigDecimal earnedPoints = BigDecimal.valueOf(earned).setScale(8, RoundingMode.HALF_UP);
            BigDecimal delta = datasetMaxPoints.subtract(earnedPoints).abs();
            boolean allChecksPassed = !failAllTriggered && delta.compareTo(new BigDecimal("0.0001")) <= 0;
            String message = issueBuilder.length() == 0
                    ? "SELECT result mismatch"
                    : issueBuilder.toString().trim();

            return SelectDatasetDecision.ruleResult(allChecksPassed, failAllTriggered,
                    allChecksPassed ? null : message, earnedPoints);
        } catch (Exception e) {
            return SelectDatasetDecision.executionFailure("Lỗi chấm SELECT: " + e.getMessage());
        }
    }

    private SelectDatasetDecision gradeSelectWithSingleDatasetStrict(
            String schemaName,
            String ddlScript,
            String datasetScript,
            String datasetLabel,
            ExamQuestion question,
            String studentQuery) {
        try {
            examSchemaService.resetSchema(schemaName, false);
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, datasetScript);

            List<Map<String, Object>> actual = examSchemaService.executeSql(schemaName, studentQuery).getResultSet();
            List<Map<String, Object>> expected = examSchemaService.executeSql(schemaName, question.getCorrectQuery()).getResultSet();

            boolean requireStrictOrder = question.getCorrectQuery() != null
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            if (!compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                return SelectDatasetDecision.strictMismatch("SELECT result mismatch on " + datasetLabel);
            }
            return SelectDatasetDecision.strictPass();
        } catch (Exception e) {
            return SelectDatasetDecision.executionFailure("Failed on " + datasetLabel + ": " + e.getMessage());
        }
    }

    private SelectDatasetDecision gradeSelectWithSingleDatasetByRules(
            String schemaName,
            String ddlScript,
            String datasetScript,
            String datasetLabel,
            ExamQuestion question,
            String studentQuery,
            JsonNode gradingRules,
            BigDecimal datasetMaxPoints) {
        try {
            examSchemaService.resetSchema(schemaName, false);
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, datasetScript);

            List<Map<String, Object>> actual = examSchemaService.executeSql(schemaName, studentQuery).getResultSet();
            List<Map<String, Object>> expected = examSchemaService.executeSql(schemaName, question.getCorrectQuery()).getResultSet();

            boolean requireStrictOrder = question.getCorrectQuery() != null
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            if (compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                return SelectDatasetDecision.ruleResult(true, false, null, datasetMaxPoints);
            }

            List<String> expectedColumns = extractSelectColumns(expected);
            List<String> actualColumns = extractSelectColumns(actual);
            List<String> comparisonColumns = !expectedColumns.isEmpty() ? expectedColumns : actualColumns;

            // Remap actual rows by column position so cell comparison works
            // even when student uses different column aliases (e.g., missing AS).
            List<Map<String, Object>> remappedActual = remapActualRowsByPosition(
                    actual, actualColumns, expectedColumns);

            int expectedRowsCount = expected == null ? 0 : expected.size();
            int actualRowsCount = actual == null ? 0 : actual.size();
            int missingRows = Math.max(0, expectedRowsCount - actualRowsCount);
            int extraRows = Math.max(0, actualRowsCount - expectedRowsCount);

            int nameMismatchAtSamePosition = 0;
            int minCols = Math.min(expectedColumns.size(), actualColumns.size());
            for (int i = 0; i < minCols; i++) {
                if (!expectedColumns.get(i).equalsIgnoreCase(actualColumns.get(i))) {
                    nameMismatchAtSamePosition++;
                }
            }
            int trulyMissingColumns = Math.max(0, expectedColumns.size() - actualColumns.size());
            int trulyExtraColumns = Math.max(0, actualColumns.size() - expectedColumns.size());

            int missingColumns = nameMismatchAtSamePosition + trulyMissingColumns;
            int extraColumns = trulyExtraColumns;

            int columnOrderViolations = 0;
            if (nameMismatchAtSamePosition == 0 && trulyMissingColumns == 0 && trulyExtraColumns == 0
                    && !expectedColumns.isEmpty() && !actualColumns.isEmpty()
                    && !sameColumnOrderIgnoreCase(expectedColumns, actualColumns)) {
                columnOrderViolations = 1;
            }

            JsonNode rowOrderRule = findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
            int rowOrderViolations = 0;
            if (requireStrictOrder && rowOrderRule != null && !hasInsertModifier(rowOrderRule, "SORT_ASC")) {
                rowOrderViolations = countSelectRowOrderViolations(remappedActual, expected, comparisonColumns);
                if (rowOrderViolations == 0) {
                    rowOrderViolations = 1;
                }
            }

            JsonNode cellNotEqualRule = findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
            JsonNode cellNullRule = findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
            JsonNode cellCompareModifiers = firstNonEmptyModifiers(
                    extractInsertRuleModifiers(cellNotEqualRule),
                    extractInsertRuleModifiers(cellNullRule));

            List<SelectRowPair> rowPairs = buildSelectRowPairs(
                    remappedActual,
                    expected,
                    comparisonColumns,
                    requireStrictOrder,
                    cellCompareModifiers);
            int wrongCells = countSelectCellMismatches(rowPairs, comparisonColumns, cellCompareModifiers);
            int nullViolations = countSelectNullViolations(rowPairs, comparisonColumns, cellCompareModifiers);

            double datasetPoints = datasetMaxPoints.doubleValue();
            int expectedColumnsCount = Math.max(1, comparisonColumns.size());
            int expectedRowsForPenalty = Math.max(1, expectedRowsCount);
            double rowPenaltyDefault = datasetPoints / expectedRowsForPenalty;
            double columnPenaltyDefault = datasetPoints / expectedColumnsCount;
            double cellPenaltyDefault = rowPenaltyDefault / expectedColumnsCount;

            List<SelectRuleApplication> applications = List.of(
                    applySelectRule(gradingRules, "ROW", "IS_MISSING", missingRows,
                            datasetMaxPoints, rowPenaltyDefault,
                            "thieu " + missingRows + " dong"),
                    applySelectRule(gradingRules, "ROW", "IS_EXTRA", extraRows,
                            datasetMaxPoints, rowPenaltyDefault,
                            "du " + extraRows + " dong"),
                    applySelectRule(gradingRules, "CELL_VALUE", "NOT_EQUAL", wrongCells,
                            datasetMaxPoints, cellPenaltyDefault,
                            "sai " + wrongCells + " o du lieu"),
                    applySelectRule(gradingRules, "CELL_VALUE", "IS_NULL", nullViolations,
                            datasetMaxPoints, cellPenaltyDefault,
                            "co " + nullViolations + " o gia tri rong"),
                    applySelectRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER", rowOrderViolations,
                            datasetMaxPoints, rowPenaltyDefault,
                            "sai thu tu " + rowOrderViolations + " dong"),
                    applySelectRule(gradingRules, "COLUMN_ORDER", "OUT_OF_ORDER", columnOrderViolations,
                            datasetMaxPoints, columnPenaltyDefault,
                            "sai thu tu cot ket qua"),
                    applySelectRule(gradingRules, "COLUMN", "IS_MISSING", missingColumns,
                            datasetMaxPoints, columnPenaltyDefault,
                            "thieu " + missingColumns + " cot"),
                    applySelectRule(gradingRules, "COLUMN", "IS_EXTRA", extraColumns,
                            datasetMaxPoints, columnPenaltyDefault,
                            "du " + extraColumns + " cot"));

            double earned = datasetPoints;
            int matchedRuleCount = 0;
            boolean failAllTriggered = false;
            StringBuilder issueBuilder = new StringBuilder();

            for (SelectRuleApplication application : applications) {
                if (!application.violationPresent()) {
                    continue;
                }

                if (application.ruleMatched()) {
                    matchedRuleCount++;
                }

                if (application.failAllTriggered()) {
                    failAllTriggered = true;
                }

                if (application.deduction().compareTo(BigDecimal.ZERO) > 0) {
                    earned -= application.deduction().doubleValue();
                }

                if (application.message() != null && !application.message().isBlank()) {
                    appendSelectIssue(issueBuilder, application.message());
                }
            }

            if (failAllTriggered) {
                earned = 0d;
            } else if (matchedRuleCount == 0) {
                // Violations exist but no rules matched -> don't deduct points
                appendSelectIssue(issueBuilder,
                        "Phát hiện sai lệch nhưng không có quy tắc chấm phù hợp trên " + datasetLabel + " -> không trừ điểm.");
            }

            if (earned < 0d) {
                earned = 0d;
            }
            if (earned > datasetPoints) {
                earned = datasetPoints;
            }

                BigDecimal earnedPoints = BigDecimal.valueOf(earned).setScale(8, RoundingMode.HALF_UP);
                BigDecimal delta = datasetMaxPoints.subtract(earnedPoints).abs();
                boolean allChecksPassed = !failAllTriggered && delta.compareTo(new BigDecimal("0.0001")) <= 0;
            String message = issueBuilder.length() == 0
                    ? "SELECT result mismatch on " + datasetLabel
                    : "[" + datasetLabel + "] " + issueBuilder.toString().trim();

            return SelectDatasetDecision.ruleResult(
                    allChecksPassed,
                    failAllTriggered,
                    allChecksPassed ? null : message,
                    earnedPoints);
        } catch (Exception e) {
            return SelectDatasetDecision.executionFailure("Failed on " + datasetLabel + ": " + e.getMessage());
        }
    }

    private JsonNode resolveSelectGradingRules(ExamQuestion question) {
        if (question == null || question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return objectMapper.createArrayNode();
        }

        try {
            JsonNode rubric = objectMapper.readTree(question.getGradingRubric());
            JsonNode payload = rubric.path("grading_payload");

            JsonNode[] candidates = new JsonNode[] {
                    payload.path("grading_rules"),
                    rubric.path("grading_rules")
            };

            for (JsonNode candidate : candidates) {
                if (candidate != null && candidate.isArray()) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            log.warn("Cannot parse grading_rules for SELECT Q{}: {}", question.getId(), e.getMessage());
        }

        return objectMapper.createArrayNode();
    }

    private boolean hasSelectGradingRules(JsonNode gradingRules) {
        if (gradingRules == null || !gradingRules.isArray()) {
            return false;
        }

        for (JsonNode ruleNode : gradingRules) {
            if (!ruleNode.isObject()) {
                continue;
            }
            String target = ruleNode.path("target").asText("").trim();
            String condition = ruleNode.path("condition").asText("").trim();
            if (!target.isBlank() && !condition.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractSelectColumns(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(rows.get(0).keySet());
    }

    private List<Map<String, Object>> remapActualRowsByPosition(
            List<Map<String, Object>> actualRows,
            List<String> actualColumns,
            List<String> expectedColumns) {
        if (actualRows == null || actualRows.isEmpty()
                || expectedColumns == null || expectedColumns.isEmpty()) {
            return actualRows != null ? actualRows : List.of();
        }

        boolean allMatch = actualColumns.size() >= expectedColumns.size();
        if (allMatch) {
            for (int i = 0; i < expectedColumns.size(); i++) {
                if (i >= actualColumns.size()
                        || !expectedColumns.get(i).equalsIgnoreCase(actualColumns.get(i))) {
                    allMatch = false;
                    break;
                }
            }
        }
        if (allMatch) {
            return actualRows;
        }

        List<Map<String, Object>> remapped = new ArrayList<>();
        for (Map<String, Object> actualRow : actualRows) {
            Map<String, Object> newRow = new LinkedHashMap<>();
            for (int i = 0; i < expectedColumns.size(); i++) {
                String expectedCol = expectedColumns.get(i);
                Object value = null;
                if (i < actualColumns.size()) {
                    String actualCol = actualColumns.get(i);
                    if (actualRow.containsKey(actualCol)) {
                        value = actualRow.get(actualCol);
                    } else {
                        for (Map.Entry<String, Object> entry : actualRow.entrySet()) {
                            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(actualCol)) {
                                value = entry.getValue();
                                break;
                            }
                        }
                    }
                }
                newRow.put(expectedCol, value);
            }
            remapped.add(newRow);
        }
        return remapped;
    }

    private int countMissingColumns(List<String> expectedColumns, List<String> actualColumns) {
        if (expectedColumns == null || expectedColumns.isEmpty()) {
            return 0;
        }

        Set<String> actualSet = new HashSet<>();
        if (actualColumns != null) {
            for (String actual : actualColumns) {
                if (actual != null) {
                    actualSet.add(actual.toLowerCase(Locale.ROOT));
                }
            }
        }

        int missing = 0;
        for (String expected : expectedColumns) {
            if (expected == null) {
                continue;
            }
            if (!actualSet.contains(expected.toLowerCase(Locale.ROOT))) {
                missing++;
            }
        }
        return missing;
    }

    private int countExtraColumns(List<String> expectedColumns, List<String> actualColumns) {
        if (actualColumns == null || actualColumns.isEmpty()) {
            return 0;
        }

        Set<String> expectedSet = new HashSet<>();
        if (expectedColumns != null) {
            for (String expected : expectedColumns) {
                if (expected != null) {
                    expectedSet.add(expected.toLowerCase(Locale.ROOT));
                }
            }
        }

        int extra = 0;
        for (String actual : actualColumns) {
            if (actual == null) {
                continue;
            }
            if (!expectedSet.contains(actual.toLowerCase(Locale.ROOT))) {
                extra++;
            }
        }
        return extra;
    }

    private boolean sameColumnOrderIgnoreCase(List<String> expectedColumns, List<String> actualColumns) {
        if (expectedColumns == null || actualColumns == null) {
            return false;
        }
        if (expectedColumns.size() != actualColumns.size()) {
            return false;
        }
        for (int i = 0; i < expectedColumns.size(); i++) {
            String expected = expectedColumns.get(i);
            String actual = actualColumns.get(i);
            if (expected == null && actual == null) {
                continue;
            }
            if (expected == null || actual == null || !expected.equalsIgnoreCase(actual)) {
                return false;
            }
        }
        return true;
    }

    private int countSelectRowOrderViolations(
            List<Map<String, Object>> actualRows,
            List<Map<String, Object>> expectedRows,
            List<String> columns) {
        if (actualRows == null || expectedRows == null) {
            return 0;
        }

        int limit = Math.min(actualRows.size(), expectedRows.size());
        int violations = 0;
        for (int i = 0; i < limit; i++) {
            String actualSig = buildSelectRowSignature(actualRows.get(i), columns);
            String expectedSig = buildSelectRowSignature(expectedRows.get(i), columns);
            if (!actualSig.equals(expectedSig)) {
                violations++;
            }
        }
        return violations;
    }

    private String buildSelectRowSignature(Map<String, Object> row, List<String> columns) {
        if (row == null) {
            return "";
        }

        StringBuilder signature = new StringBuilder();
        if (columns != null && !columns.isEmpty()) {
            for (String column : columns) {
                signature.append(normalizeValue(getRowValueIgnoreCase(row, column))).append("|||");
            }
            return signature.toString().toLowerCase(Locale.ROOT);
        }

        for (Object value : row.values()) {
            signature.append(normalizeValue(value)).append("|||");
        }
        return signature.toString().toLowerCase(Locale.ROOT);
    }

    private List<SelectRowPair> buildSelectRowPairs(
            List<Map<String, Object>> actualRows,
            List<Map<String, Object>> expectedRows,
            List<String> columns,
            boolean strictOrdering,
            JsonNode cellModifiers) {
        if (actualRows == null || expectedRows == null || columns == null || columns.isEmpty()) {
            return List.of();
        }

        List<SelectRowPair> pairs = new ArrayList<>();
        if (strictOrdering) {
            int limit = Math.min(actualRows.size(), expectedRows.size());
            for (int i = 0; i < limit; i++) {
                pairs.add(new SelectRowPair(actualRows.get(i), expectedRows.get(i)));
            }
            return pairs;
        }

        List<Map<String, Object>> remainingActualRows = new ArrayList<>(actualRows);
        for (Map<String, Object> expectedRow : expectedRows) {
            int matchedIndex = findBestSelectRowMatchIndex(
                    remainingActualRows,
                    expectedRow,
                    columns,
                    cellModifiers);
            if (matchedIndex < 0) {
                continue;
            }

            Map<String, Object> matchedRow = remainingActualRows.remove(matchedIndex);
            pairs.add(new SelectRowPair(matchedRow, expectedRow));
        }

        return pairs;
    }

    private int findBestSelectRowMatchIndex(
            List<Map<String, Object>> actualRows,
            Map<String, Object> expectedRow,
            List<String> columns,
            JsonNode cellModifiers) {
        if (actualRows == null || actualRows.isEmpty()) {
            return -1;
        }

        int bestIndex = -1;
        int bestScore = -1;
        for (int i = 0; i < actualRows.size(); i++) {
            int score = scoreSelectRowMatch(actualRows.get(i), expectedRow, columns, cellModifiers);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private int scoreSelectRowMatch(
            Map<String, Object> actualRow,
            Map<String, Object> expectedRow,
            List<String> columns,
            JsonNode cellModifiers) {
        if (actualRow == null || expectedRow == null || columns == null || columns.isEmpty()) {
            return 0;
        }

        int score = 0;
        for (String column : columns) {
            Object actualValue = getRowValueIgnoreCase(actualRow, column);
            Object expectedValue = getRowValueIgnoreCase(expectedRow, column);
            if (valuesEqualByMatchTypeWithModifiers(
                    actualValue,
                    expectedValue,
                    "EXACT",
                    cellModifiers,
                    false,
                    false)) {
                score++;
            }
        }
        return score;
    }

    private int countSelectCellMismatches(
            List<SelectRowPair> rowPairs,
            List<String> columns,
            JsonNode cellModifiers) {
        if (rowPairs == null || rowPairs.isEmpty() || columns == null || columns.isEmpty()) {
            return 0;
        }

        int mismatches = 0;
        for (SelectRowPair rowPair : rowPairs) {
            for (String column : columns) {
                Object actualValue = getRowValueIgnoreCase(rowPair.actualRow(), column);
                Object expectedValue = getRowValueIgnoreCase(rowPair.expectedRow(), column);

                boolean equals = valuesEqualByMatchTypeWithModifiers(
                        actualValue,
                        expectedValue,
                        "EXACT",
                        cellModifiers,
                        false,
                        false);
                if (!equals) {
                    mismatches++;
                }
            }
        }

        return mismatches;
    }

    private int countSelectNullViolations(
            List<SelectRowPair> rowPairs,
            List<String> columns,
            JsonNode cellModifiers) {
        if (rowPairs == null || rowPairs.isEmpty() || columns == null || columns.isEmpty()) {
            return 0;
        }

        int nullViolations = 0;
        for (SelectRowPair rowPair : rowPairs) {
            for (String column : columns) {
                Object actualValue = getRowValueIgnoreCase(rowPair.actualRow(), column);
                Object expectedValue = getRowValueIgnoreCase(rowPair.expectedRow(), column);

                String normalizedActual = applyInsertModifiers(
                        normalizeValueStr(actualValue, false, false),
                        cellModifiers);
                String normalizedExpected = applyInsertModifiers(
                        normalizeValueStr(expectedValue, false, false),
                        cellModifiers);

                if (!isNullLike(normalizedExpected) && isNullLike(normalizedActual)) {
                    nullViolations++;
                }
            }
        }

        return nullViolations;
    }

    private SelectRuleApplication applySelectRule(
            JsonNode gradingRules,
            String target,
            String condition,
            int violationCount,
            BigDecimal datasetMaxPoints,
            double defaultPenaltyPerViolation,
            String violationSummary) {
        if (violationCount <= 0) {
            return SelectRuleApplication.noViolation();
        }

        JsonNode ruleNode = findInsertRule(gradingRules, target, condition);
        if (ruleNode == null) {
            return SelectRuleApplication.unmatchedViolation();
        }

        SelectRuleDecision decision = resolveSelectRuleDecision(
                ruleNode,
                datasetMaxPoints,
                defaultPenaltyPerViolation);

        String ruleLabel = selectRuleLabel(target, condition);
        if (decision.ignore()) {
            String message = String.format(
                    Locale.ROOT,
                    "Rule %s bo qua vi pham (%s).",
                    ruleLabel,
                    violationSummary);
            return SelectRuleApplication.matchedViolation(false, BigDecimal.ZERO, message);
        }

        if (decision.failAll()) {
            String message = String.format(
                    Locale.ROOT,
                    "Rule %s kich hoat FAIL_ALL (%s).",
                    ruleLabel,
                    violationSummary);
            return SelectRuleApplication.matchedViolation(true, BigDecimal.ZERO, message);
        }

        BigDecimal deduction = BigDecimal.valueOf(Math.max(0d, decision.penaltyPerViolation()))
                .multiply(BigDecimal.valueOf(violationCount));

        String message = buildSelectRuleMessage(
                ruleLabel,
                violationSummary,
                deduction,
                decision.action());
        return SelectRuleApplication.matchedViolation(false, deduction, message);
    }

    private SelectRuleDecision resolveSelectRuleDecision(
            JsonNode ruleNode,
            BigDecimal datasetMaxPoints,
            double defaultPenaltyPerViolation) {
        String action = ruleNode != null ? ruleNode.path("action").asText("").trim() : "";
        if (action.isBlank()) {
            action = "DEDUCT_POINTS";
        }

        String normalizedAction = action.toUpperCase(Locale.ROOT);
        double safeDefaultPenalty = Math.max(0d, defaultPenaltyPerViolation);
        double penaltyValue = ruleNode != null
                ? readDoubleSetting(ruleNode.path("penalty_value"), -1d)
                : -1d;

        switch (normalizedAction) {
            case "IGNORE":
                return new SelectRuleDecision(normalizedAction, 0d, true, false);
            case "FAIL_ALL":
                return new SelectRuleDecision(normalizedAction, 0d, false, true);
            case "FAIL_ITEM":
                return new SelectRuleDecision(normalizedAction, safeDefaultPenalty, false, false);
            case "DEDUCT_PERCENTAGE": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, datasetMaxPoints.doubleValue() * penaltyValue / 100d)
                        : safeDefaultPenalty;
                return new SelectRuleDecision(normalizedAction, penalty, false, false);
            }
            case "DEDUCT_POINTS": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, penaltyValue)
                        : safeDefaultPenalty;
                return new SelectRuleDecision(normalizedAction, penalty, false, false);
            }
            default:
                return new SelectRuleDecision("DEDUCT_POINTS", safeDefaultPenalty, false, false);
        }
    }

    private String selectRuleLabel(String target, String condition) {
        return target.toUpperCase(Locale.ROOT) + "/" + condition.toUpperCase(Locale.ROOT);
    }

    private String buildSelectRuleMessage(
            String ruleLabel,
            String violationSummary,
            BigDecimal deduction,
            String action) {
        String formattedDeduction = deduction.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return String.format(
                Locale.ROOT,
                "Rule %s (%s, action=%s): tru %s diem.",
                ruleLabel,
                violationSummary,
                action,
                formattedDeduction);
    }

    private void appendSelectIssue(StringBuilder builder, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(message.trim());
    }

    private record SelectDatasetSpec(String datasetLabel, String datasetScript) {
    }

    private record SelectRowPair(Map<String, Object> actualRow, Map<String, Object> expectedRow) {
    }

    private record SelectRuleDecision(String action, double penaltyPerViolation, boolean ignore, boolean failAll) {
    }

    private record SelectRuleApplication(
            boolean violationPresent,
            boolean ruleMatched,
            boolean failAllTriggered,
            BigDecimal deduction,
            String message) {
        static SelectRuleApplication noViolation() {
            return new SelectRuleApplication(false, false, false, BigDecimal.ZERO, null);
        }

        static SelectRuleApplication unmatchedViolation() {
            return new SelectRuleApplication(true, false, false, BigDecimal.ZERO, null);
        }

        static SelectRuleApplication matchedViolation(boolean failAllTriggered, BigDecimal deduction, String message) {
            return new SelectRuleApplication(true, true, failAllTriggered, deduction, message);
        }
    }

    private record SelectDatasetDecision(
            boolean executionFailed,
            boolean allChecksPassed,
            boolean failAllTriggered,
            String errorMessage,
            BigDecimal earnedPoints) {
        static SelectDatasetDecision strictPass() {
            return new SelectDatasetDecision(false, true, false, null, BigDecimal.ZERO);
        }

        static SelectDatasetDecision strictMismatch(String message) {
            return new SelectDatasetDecision(false, false, false, message, BigDecimal.ZERO);
        }

        static SelectDatasetDecision executionFailure(String message) {
            return new SelectDatasetDecision(true, false, false, message, BigDecimal.ZERO);
        }

        static SelectDatasetDecision ruleResult(
                boolean allChecksPassed,
                boolean failAllTriggered,
                String errorMessage,
                BigDecimal earnedPoints) {
            return new SelectDatasetDecision(false, allChecksPassed, failAllTriggered, errorMessage, earnedPoints);
        }
    }

    private record GradeDecision(boolean isCorrect, String errorMessage, BigDecimal scoreEarned) {
        static GradeDecision pass(BigDecimal scoreEarned) {
            return new GradeDecision(true, null, scoreEarned == null ? BigDecimal.ZERO : scoreEarned);
        }

        static GradeDecision fail(String message) {
            return new GradeDecision(false, message, BigDecimal.ZERO);
        }

        static GradeDecision partial(BigDecimal scoreEarned, String message) {
            return new GradeDecision(false, message, scoreEarned == null ? BigDecimal.ZERO : scoreEarned);
        }
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

    private void setAllConstraintsEnabled(String schemaName, boolean enabled) {
        String safeSchema = schemaName.replaceAll("[^a-zA-Z0-9_]", "");
        List<Map<String, Object>> tables = examSchemaService.executeAdminSql(
                "SELECT t.name AS TABLE_NAME "
                        + "FROM sys.tables t "
                        + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
                        + "WHERE s.name = '" + safeSchema + "'").getResultSet();

        for (Map<String, Object> row : tables) {
            Object tableNameObj = row.get("TABLE_NAME");
            if (tableNameObj == null) {
                continue;
            }

            String tableName = String.valueOf(tableNameObj).replaceAll("[^a-zA-Z0-9_]", "");
            String sql = enabled
                    ? "ALTER TABLE [" + safeSchema + "].[" + tableName + "] WITH CHECK CHECK CONSTRAINT ALL"
                    : "ALTER TABLE [" + safeSchema + "].[" + tableName + "] NOCHECK CONSTRAINT ALL";

            try {
                examSchemaService.executeAdminSql(sql);
            } catch (Exception e) {
                log.warn("Failed to {} constraints for table {}: {}",
                        enabled ? "enable" : "disable", tableName, e.getMessage());
            }
        }
    }
}
