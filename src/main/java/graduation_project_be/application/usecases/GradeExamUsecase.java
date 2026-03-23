package graduation_project_be.application.usecases;

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
                            try {
                                examSchemaService.executeSql(schemaName, studentQuery);
                            } catch (Exception execErr) {
                                errorMessage = "Cảnh báo Lỗi Execute: " + execErr.getMessage();
                            }
                            isCorrect = gradeAnswer(schemaName, teacherSchemaName, question, submission);
                            
                            if (submission.getErrorMessage() != null && !submission.getErrorMessage().isBlank()) {
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
                      double colPoints = 0.5 * perColScore; // 50% for existing column
                      
                      // 30% for type match
                      if (actualCol.getDataType().equalsIgnoreCase(expectedCol.getDataType())) {
                          colPoints += 0.3 * perColScore;
                      } else {
                          errorBuilder.append(String.format("Bảng %s: cột %s sai kiểu dữ liệu (Kỳ vọng: %s, Thực tế: %s). ", 
                              expectedTable.getTableName(), expectedCol.getColumnName(), expectedCol.getDataType(), actualCol.getDataType()));
                          allPassed = false;
                      }
                      
                      // 10% for PK match
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
                      
                      // 10% for FK match
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

    private boolean gradeInsertDataAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question, ExamSubmission submission) {
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
                List<Map<String, Object>> tcRes = examSchemaService.executeAdminSql("SELECT COUNT(*) as cnt FROM [" + teacherSchemaName + "]." + tName);
                long tCount = ((Number) tcRes.get(0).values().iterator().next()).longValue();
                
                if (tCount == 0) {
                     earnedTotal = earnedTotal.add(perTablePoints); // table not required to have data
                     continue;
                }

                // 2. student count
                long sCount = 0;
                try {
                    List<Map<String, Object>> scRes = examSchemaService.executeAdminSql("SELECT COUNT(*) as cnt FROM [" + schemaName + "]." + tName);
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
                    List<Map<String, Object>> mRes = examSchemaService.executeAdminSql(missingSql);
                    missingCount = ((Number) mRes.get(0).values().iterator().next()).longValue();
                } catch (Exception e) {
                    missingCount = tCount; // fallback
                }

                // 4. extra count
                long extraCount = 0;
                try {
                    String extraSql = "SELECT COUNT(*) FROM (SELECT * FROM [" + schemaName + "]." + tName + " EXCEPT SELECT * FROM [" + teacherSchemaName + "]." + tName + ") b";
                    List<Map<String, Object>> eRes = examSchemaService.executeAdminSql(extraSql);
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
                
                List<Map<String, Object>> actual = examSchemaService.executeAdminSql(validationQuery);
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
