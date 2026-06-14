package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.TableMetadata.ColumnMetadata;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSubmission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import graduation_project_be.application.usecases.GradingTraceCollector;
import graduation_project_be.application.usecases.CreateTableRubricEvaluator;

/** Grades CREATE_TABLE questions (rubric V2 + algorithmic structural compare). */
@Slf4j
@RequiredArgsConstructor
public class CreateTableQuestionGrader {

    private final ExamSchemaService examSchemaService;
    private final ObjectMapper objectMapper;
    private final GradingSupport support;

    public boolean gradeCreateTableAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission) {
        // === Check for rubric-based grading ===
        if (question.getGradingRubric() != null && !question.getGradingRubric().isBlank()) {
            try {
                return gradeCreateTableByRubricV2(schemaName, question, submission);
            } catch (Exception e) {
                log.warn("Chấm theo rubric thất bại cho câu {}, chuyển sang chấm thuật toán: {}", question.getId(),
                        e.getMessage());
            }
        }

        // === Fallback: existing algorithmic grading ===
        List<TableMetadata> expectedTables = examSchemaService.extractMetadata(teacherSchemaName);
        List<TableMetadata> actualTables = examSchemaService.extractMetadata(schemaName);

        if (expectedTables == null || expectedTables.isEmpty()) {
            return support.gradeByTestCases(schemaName, teacherSchemaName, question, submission);
        }

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        BigDecimal perTablePoints = totalPoints.divide(BigDecimal.valueOf(expectedTables.size()), 4,
                RoundingMode.HALF_UP);
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
                        errorBuilder
                                .append(String.format("Bảng %s: cột %s sai kiểu dữ liệu (Kỳ vọng: %s, Thực tế: %s). ",
                                        expectedTable.getTableName(), expectedCol.getColumnName(),
                                        expectedCol.getDataType(), actualCol.getDataType()));
                        allPassed = false;
                    }
                    if (actualCol.isPrimaryKey() == expectedCol.isPrimaryKey()) {
                        colPoints += 0.1 * perColScore;
                    } else {
                        if (expectedCol.isPrimaryKey()) {
                            errorBuilder.append(String.format("Bảng %s: thiếu khoá chính ở cột %s. ",
                                    expectedTable.getTableName(), expectedCol.getColumnName()));
                        } else {
                            errorBuilder.append(String.format("Bảng %s: dư định nghĩa khoá chính ở cột %s. ",
                                    expectedTable.getTableName(), expectedCol.getColumnName()));
                        }
                        allPassed = false;
                    }
                    if (expectedCol.isForeignKey()) {
                        if (actualCol.isForeignKey() &&
                                expectedCol.getReferencesTable().equalsIgnoreCase(actualCol.getReferencesTable())) {
                            colPoints += 0.1 * perColScore;
                        } else {
                            errorBuilder.append(String.format("Bảng %s, cột %s: thiếu khoá ngoại tham chiếu %s. ",
                                    expectedTable.getTableName(), expectedCol.getColumnName(),
                                    expectedCol.getReferencesTable()));
                            allPassed = false;
                        }
                    } else {
                        if (!actualCol.isForeignKey()) {
                            colPoints += 0.1 * perColScore;
                        } else {
                            errorBuilder.append(String.format("Bảng %s, cột %s: bị dư khoá ngoại sai đề. ",
                                    expectedTable.getTableName(), expectedCol.getColumnName()));
                            allPassed = false;
                        }
                    }
                    tableScore += colPoints;
                } else {
                    allPassed = false;
                    errorBuilder.append(String.format("Bảng %s: thiếu cột %s. ", expectedTable.getTableName(),
                            expectedCol.getColumnName()));
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

        if (GradingTraceCollector.isActive()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_SUMMARY,
                    allPassed ? GradingTraceItem.STATUS_PASS : GradingTraceItem.STATUS_FAIL,
                    "Kiểm tra cấu trúc bảng",
                    allPassed ? "Tất cả bảng và cột đúng cấu trúc" : errorBuilder.toString().trim(),
                    null, null, null, null, null, null,
                    earnedTotal, totalPoints, allPassed ? null : totalPoints.subtract(earnedTotal),
                    null, null,
                    "So sánh metadata bảng/cột/khóa giữa schema SV và schema GV"));
        }

        return allPassed;
    }

    private boolean gradeCreateTableByRubricV2(String schemaName, ExamQuestion question, ExamSubmission submission) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(question.getGradingRubric());
        } catch (Exception e) {
            throw new RuntimeException("Rubric JSON không hợp lệ: " + e.getMessage());
        }

        List<TableMetadata> actualTables = examSchemaService.extractMetadata(schemaName);
        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        CreateTableRubricEvaluator.CreateTableRubricGradeResult result = CreateTableRubricEvaluator.evaluate(rubric,
                actualTables, totalPoints);

        if (submission != null) {
            submission.setScoreEarned(result.earnedPoints());
            submission.setErrorMessage(result.errorMessage());
        }

        if (GradingTraceCollector.isActive()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_SUMMARY,
                    result.allPassed() ? GradingTraceItem.STATUS_PASS : GradingTraceItem.STATUS_FAIL,
                    "Kiểm tra CREATE TABLE (rubric)",
                    result.allPassed() ? "Tất cả kiểm tra rubric đạt"
                            : (result.errorMessage() != null ? result.errorMessage() : "Rubric kiểm tra thất bại"),
                    null, null, null, null, null, null,
                    result.earnedPoints(), totalPoints,
                    result.allPassed() ? null : totalPoints.subtract(result.earnedPoints()),
                    null, null,
                    "Chấm theo rubric CREATE TABLE"));

            if (result.details() != null) {
                for (Map<String, Object> detail : result.details()) {
                    String type = String.valueOf(detail.getOrDefault("type", "info"));
                    String msg = String.valueOf(detail.getOrDefault("message", ""));
                    Object pts = detail.get("points");
                    BigDecimal pointsVal = pts != null ? new BigDecimal(pts.toString()) : null;
                    boolean isError = "error".equals(type);

                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_RUBRIC_RULE,
                            isError ? GradingTraceItem.STATUS_FAIL : GradingTraceItem.STATUS_INFO,
                            msg.length() > 60 ? msg.substring(0, 57) + "..." : msg,
                            msg,
                            null, null,
                            null, null,
                            isError ? "DEDUCT_POINTS" : null,
                            isError && pointsVal != null ? pointsVal.abs() : null,
                            null, null,
                            isError && pointsVal != null ? pointsVal.abs() : null,
                            null, null,
                            "CREATE TABLE rubric rule"));
                }
            }
        }

        return result.allPassed();
    }
}
