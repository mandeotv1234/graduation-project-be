package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.domain.models.TableMetadata;
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

/** Grades INSERT_DATA questions (rubric deduction + algorithmic row/cell compare). */
@Slf4j
@RequiredArgsConstructor
public class InsertDataQuestionGrader {

    private final ExamSchemaService examSchemaService;
    private final ObjectMapper objectMapper;
    private final GradingSupport support;

    public boolean gradeInsertDataAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission, boolean fallbackTriggered) {
        // === Check for rubric-based grading ===
        if (question.getGradingRubric() != null && !question.getGradingRubric().isBlank()) {
            try {
                return gradeInsertDataByRubric(schemaName, question, submission, fallbackTriggered);
            } catch (Exception e) {
                log.warn("Chấm theo rubric thất bại cho câu {}, chuyển sang chấm thuật toán cũ: {}",
                        question.getId(), e.getMessage());
            }
        }

        // === Fallback: existing algorithmic grading ===
        List<TableMetadata> expectedTables = examSchemaService.extractMetadata(teacherSchemaName);

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
            String tName = expectedTable.getTableName();

            try {
                // 1. teacher count
                List<Map<String, Object>> tcRes = examSchemaService
                        .executeAdminSql("SELECT COUNT(*) as cnt FROM [" + teacherSchemaName + "]." + tName)
                        .getResultSet();
                long tCount = ((Number) tcRes.get(0).values().iterator().next()).longValue();

                if (tCount == 0) {
                    earnedTotal = earnedTotal.add(perTablePoints); // table not required to have data
                    continue;
                }

                // 2. student count
                long sCount = 0;
                try {
                    List<Map<String, Object>> scRes = examSchemaService
                            .executeAdminSql("SELECT COUNT(*) as cnt FROM [" + schemaName + "]." + tName)
                            .getResultSet();
                    sCount = ((Number) scRes.get(0).values().iterator().next()).longValue();
                } catch (Exception e) {
                    allPassed = false;
                    errorBuilder.append(String.format("Bảng %s lỗi trống rỗng hoặc chưa được tạo. ", tName));
                    continue; // 0 points for this table
                }

                // 3. missing count
                long missingCount = 0;
                try {
                    String missingSql = "SELECT COUNT(*) FROM (SELECT * FROM [" + teacherSchemaName + "]." + tName
                            + " EXCEPT SELECT * FROM [" + schemaName + "]." + tName + ") a";
                    List<Map<String, Object>> mRes = examSchemaService.executeAdminSql(missingSql).getResultSet();
                    missingCount = ((Number) mRes.get(0).values().iterator().next()).longValue();
                } catch (Exception e) {
                    missingCount = tCount; // fallback
                }

                // 4. extra count
                long extraCount = 0;
                try {
                    String extraSql = "SELECT COUNT(*) FROM (SELECT * FROM [" + schemaName + "]." + tName
                            + " EXCEPT SELECT * FROM [" + teacherSchemaName + "]." + tName + ") b";
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
                    errorBuilder.append(
                            String.format("Bảng %s: thiếu %d dòng, dư/sai %d dòng. ", tName, missingCount, extraCount));
                }
            } catch (Exception e) {
                log.warn("Không thể chấm INSERT DATA bằng thuật toán cho bảng {}", tName, e);
                allPassed = false;
                errorBuilder.append(String.format("Lỗi hệ thống khi chấm bảng %s. ", tName));
            }
        }

        if (earnedTotal.compareTo(totalPoints) > 0)
            earnedTotal = totalPoints;

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        return allPassed;
    }

    public boolean gradeInsertDataByRubric(String schemaName, ExamQuestion question, ExamSubmission submission,
            boolean fallbackTriggered) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(question.getGradingRubric());
        } catch (Exception e) {
            throw new RuntimeException("Rubric JSON không hợp lệ: " + e.getMessage());
        }

        JsonNode payload = resolveInsertPayload(rubric);
        JsonNode settings = payload.path("grading_settings");
        JsonNode gradingRules = resolveInsertGradingRules(rubric, payload);
        boolean trimSpaces = support.readBooleanSetting(settings.path("trim_string_spaces"), true);
        boolean caseInsensitive = support.readBooleanSetting(settings.path("case_insensitive_data"), false);
        boolean hasLegacyExtraRowSettings = hasLegacyInsertExtraRowSettings(settings);
        boolean allowExtraRows = support.readBooleanSetting(settings.path("allow_extra_rows"), false);
        double penaltyPerExtraRow = Math.max(0d, support.readDoubleSetting(settings.path("penalty_per_extra_row"), 0.1d));
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

        JsonNode missingRowRule = support.findInsertRule(gradingRules, "ROW", "IS_MISSING");
        JsonNode extraRowRule = support.findInsertRule(gradingRules, "ROW", "IS_EXTRA");
        JsonNode cellNotEqualRule = support.findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
        JsonNode cellNullRule = support.findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
        JsonNode rowOrderRule = support.findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
        JsonNode fkRule = support.findInsertRule(gradingRules, "FOREIGN_KEY", "REFERENCE_ERROR");

        if (fallbackTriggered && fkRule != null && fkRule.isObject()) {
            InsertRuleDecision fkDecision = resolveInsertRuleDecision(fkRule, totalPoints.doubleValue(), 0d);
            if (!fkDecision.ignore()) {
                BigDecimal questionMaxPoints = totalPoints;
                addInsertRuleTrace(
                        "schema",
                        "FOREIGN_KEY",
                        "REFERENCE_ERROR",
                        1,
                        fkDecision,
                        0d,
                        questionMaxPoints,
                        "Vi phạm khóa ngoại/ràng buộc, hệ thống tự động chạy lại theo cấu hình rubric.");
                allPassed = false;
                if (fkDecision.failAll()) {
                    failAllTriggered = true;
                } else {
                    double deduction = fkDecision.penaltyPoints();
                    if (deduction > 0d) {
                        totalPoints = BigDecimal.valueOf(Math.max(0d, totalPoints.doubleValue() - deduction));
                        errorBuilder.append(String.format(
                                "Lỗi khóa ngoại (FK): vi phạm tham chiếu/ràng buộc, hệ thống tự động chạy lại (trừ %.2f điểm). ",
                                deduction));
                    }
                }
            }
        }

        JsonNode rowMatchModifiers = support.firstNonEmptyModifiers(
                support.extractInsertRuleModifiers(missingRowRule),
                support.extractInsertRuleModifiers(cellNotEqualRule));
        JsonNode cellCompareModifiers = support.extractInsertRuleModifiers(cellNotEqualRule);
        JsonNode cellNullModifiers = support.firstNonEmptyModifiers(
                support.extractInsertRuleModifiers(cellNullRule),
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
            if (expectedRows == null || expectedRows.isMissingNode() || !expectedRows.isArray()
                    || expectedRows.size() == 0) {
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
                actualRows = examSchemaService.executeAdminSql("SELECT * FROM [" + schemaName + "]." + tableName)
                        .getResultSet();
            } catch (Exception e) {
                allPassed = false;
                errorBuilder.append(String.format("Bảng %s bị lỗi hoặc không tồn tại. ", tableName));
                addInsertRuleTrace(
                        tableName,
                        "TABLE",
                        "IS_MISSING",
                        1,
                        new InsertRuleDecision("FAIL_ALL", tablePoints, false, true),
                        tablePoints,
                        BigDecimal.valueOf(tablePoints),
                        "Không đọc được bảng sinh viên: " + e.getMessage());
                continue;
            }

            double earnedTable = tablePoints;
            boolean[] usedActualRows = new boolean[actualRows.size()];
            List<Integer> matchedActualIndexes = new ArrayList<>();

            int missingRows = 0;
            int wrongCells = 0;
            int notEqualCells = 0;
            int nullCells = 0;
            int outOfOrderRows = 0;
            InsertRuleDecision missingDecisionForTrace = null;
            InsertRuleDecision cellNotEqualDecisionForTrace = null;
            InsertRuleDecision cellNullDecisionForTrace = null;
            InsertRuleDecision rowOrderDecisionForTrace = null;
            InsertRuleDecision extraDecisionForTrace = null;

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
                            if (!support.valuesEqualByMatchTypeWithModifiers(
                                    support.getRowValueIgnoreCase(candidate, pk),
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
                            if (support.valuesEqualByMatchTypeWithModifiers(
                                    support.getRowValueIgnoreCase(candidate, col),
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
                    InsertRuleDecision missingDecision = resolveInsertRuleDecision(missingRowRule, tablePoints,
                            rowPenalty);
                    if (!missingDecision.ignore()) {
                        missingDecisionForTrace = missingDecision;
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
                    Object actualRawValue = support.getRowValueIgnoreCase(actualRow, col);
                    String expectedRawValue = getExpectedValueAsText(expectedRow, col);
                    String matchType = columnMatchTypes.getOrDefault(col, "EXACT");

                    boolean nullViolation = false;
                    if (cellNullRule != null) {
                        String normalizedActualForNull = support.applyInsertModifiers(
                                support.normalizeValueStr(actualRawValue, trimSpaces, caseInsensitive),
                                cellNullModifiers);
                        String normalizedExpectedForNull = support.applyInsertModifiers(
                                support.normalizeValueStr(expectedRawValue, trimSpaces, caseInsensitive),
                                cellNullModifiers);
                        nullViolation = !support.isNullLike(normalizedExpectedForNull) && support.isNullLike(normalizedActualForNull);
                    }

                    boolean isEqual = support.valuesEqualByMatchTypeWithModifiers(
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
                    InsertRuleDecision cellDecision = resolveInsertRuleDecision(activeRule, tablePoints,
                            defaultPenalty);

                    if (cellDecision.ignore()) {
                        continue;
                    }

                    allPassed = false;
                    wrongCells++;
                    if (nullViolation) {
                        nullCells++;
                        cellNullDecisionForTrace = cellDecision;
                    } else {
                        notEqualCells++;
                        cellNotEqualDecisionForTrace = cellDecision;
                    }

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

            if (earnedTable > 0d && rowOrderRule != null && !support.hasInsertModifier(rowOrderRule, "SORT_ASC")) {
                outOfOrderRows = countInsertOutOfOrderViolations(matchedActualIndexes);
                if (outOfOrderRows > 0) {
                    InsertRuleDecision rowOrderDecision = resolveInsertRuleDecision(rowOrderRule, tablePoints,
                            rowPenalty);
                    if (!rowOrderDecision.ignore()) {
                        rowOrderDecisionForTrace = rowOrderDecision;
                        allPassed = false;
                        if (rowOrderDecision.failAll()) {
                            failAllTriggered = true;
                            earnedTable = 0d;
                        } else {
                            earnedTable = applyInsertPenalty(earnedTable, rowOrderDecision.penaltyPoints(),
                                    outOfOrderRows);
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
                    InsertRuleDecision extraDecision = resolveInsertRuleDecision(extraRowRule, tablePoints,
                            defaultExtraPenalty);

                    if (!extraDecision.ignore()) {
                        extraDecisionForTrace = extraDecision;
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
                        double penaltyPerExtra = tablePoints * penaltyPerExtraRow;
                        extraDecisionForTrace = new InsertRuleDecision("DEDUCT_POINTS", penaltyPerExtra, false, false);
                        earnedTable = applyInsertPenalty(earnedTable, penaltyPerExtra * extraRows, 1);
                    } else {
                        extraDecisionForTrace = new InsertRuleDecision("FAIL_ALL", tablePoints, false, true);
                        earnedTable = 0d;
                    }
                }
            }

            addInsertRuleTrace(
                    tableName,
                    "ROW",
                    "IS_MISSING",
                    missingRows,
                    missingDecisionForTrace,
                    rowPenalty,
                    BigDecimal.valueOf(tablePoints),
                    null);
            addInsertRuleTrace(
                    tableName,
                    "CELL_VALUE",
                    "NOT_EQUAL",
                    notEqualCells,
                    cellNotEqualDecisionForTrace,
                    fallbackColPenalty,
                    BigDecimal.valueOf(tablePoints),
                    null);
            addInsertRuleTrace(
                    tableName,
                    "CELL_VALUE",
                    "IS_NULL",
                    nullCells,
                    cellNullDecisionForTrace,
                    fallbackColPenalty,
                    BigDecimal.valueOf(tablePoints),
                    null);
            addInsertRuleTrace(
                    tableName,
                    "ROW_ORDER",
                    "OUT_OF_ORDER",
                    outOfOrderRows,
                    rowOrderDecisionForTrace,
                    rowPenalty,
                    BigDecimal.valueOf(tablePoints),
                    null);
            addInsertRuleTrace(
                    tableName,
                    "ROW",
                    "IS_EXTRA",
                    extraRows,
                    extraDecisionForTrace,
                    Math.max(rowPenalty, tablePoints * penaltyPerExtraRow),
                    BigDecimal.valueOf(tablePoints),
                    null);

            if (missingRows > 0 || wrongCells > 0 || extraRows > 0 || outOfOrderRows > 0) {
                double tableDeduction = Math.max(0d, tablePoints - Math.max(0d, earnedTable));
                errorBuilder.append(String.format(
                        Locale.ROOT,
                        "Bảng %s: thiếu %d dòng, sai %d ô, dư %d dòng, sai thứ tự %d dòng, trừ %.2f điểm. ",
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
                errorBuilder.insert(0, "Rubric đang để FAIL_ALL: có lỗi nên câu này bị 0 điểm toàn bộ. ");
            } else {
                errorBuilder.append("Rubric đang để FAIL_ALL: có lỗi nên câu này bị 0 điểm toàn bộ.");
            }
        }

        if (earnedTotal.compareTo(totalPoints) > 0)
            earnedTotal = totalPoints;
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0)
            earnedTotal = BigDecimal.ZERO;

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed || failAllTriggered) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        boolean insertPassed = allPassed && !failAllTriggered;
        if (GradingTraceCollector.isActive()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_SUMMARY,
                    insertPassed ? GradingTraceItem.STATUS_PASS : GradingTraceItem.STATUS_FAIL,
                    "Kiểm tra INSERT DATA",
                    insertPassed ? "Tất cả dữ liệu khớp"
                            : (errorBuilder.length() > 0 ? errorBuilder.toString().trim() : "Dữ liệu không khớp"),
                    null, null, null, null, null, null,
                    earnedTotal, totalPoints,
                    insertPassed ? null : totalPoints.subtract(earnedTotal),
                    null, null,
                    "So sánh dữ liệu INSERT giữa schema SV và GV"));

        }

        return insertPassed;
    }

    private record InsertRuleDecision(String action, double penaltyPoints, boolean ignore, boolean failAll) {
    }

    private void addInsertRuleTrace(
            String tableName,
            String target,
            String condition,
            int violationCount,
            InsertRuleDecision decision,
            double defaultPenaltyPoints,
            BigDecimal tableMaxPoints,
            String overrideMessage) {
        if (!GradingTraceCollector.isActive() || violationCount <= 0 || decision == null || decision.ignore()) {
            return;
        }

        BigDecimal safeMaxPoints = tableMaxPoints != null ? tableMaxPoints : BigDecimal.ZERO;
        BigDecimal configuredPenalty = BigDecimal.valueOf(Math.max(0d,
                decision.penaltyPoints() > 0d ? decision.penaltyPoints() : defaultPenaltyPoints))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deductedPoints = decision.failAll()
                ? safeMaxPoints
                : configuredPenalty.multiply(BigDecimal.valueOf(violationCount));
        if (deductedPoints.compareTo(safeMaxPoints) > 0) {
            deductedPoints = safeMaxPoints;
        }

        String ruleLabel = target + "/" + condition;
        String message = overrideMessage != null && !overrideMessage.isBlank()
                ? overrideMessage
                : String.format(
                        Locale.ROOT,
                        "Bảng %s có %d vi phạm %s, action=%s.",
                        tableName,
                        violationCount,
                        ruleLabel,
                        decision.action());

        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_RUBRIC_RULE,
                GradingTraceItem.STATUS_FAIL,
                "Bảng " + tableName + ": " + ruleLabel,
                message,
                null,
                null,
                target,
                condition,
                decision.action(),
                configuredPenalty,
                null,
                safeMaxPoints,
                deductedPoints.setScale(2, RoundingMode.HALF_UP),
                null,
                null,
                "INSERT DATA rubric rule"));
    }

    private InsertRuleDecision resolveInsertRuleDecision(JsonNode ruleNode, double tablePoints,
            double defaultPenaltyPoints) {
        String action = ruleNode != null ? ruleNode.path("action").asText("").trim() : "";
        if (action.isBlank()) {
            action = "DEDUCT_POINTS";
        }

        String normalizedAction = action.toUpperCase(Locale.ROOT);
        double safeDefaultPenalty = Math.max(0d, defaultPenaltyPoints);
        double penaltyValue = ruleNode != null
                ? support.readDoubleSetting(ruleNode.path("penalty_value"), -1d)
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
        JsonNode ruleNode = support.findInsertRule(gradingRules, target, condition);
        if (ruleNode == null) {
            return null;
        }

        String action = ruleNode.path("action").asText("").trim();
        if (action.isBlank()) {
            return null;
        }

        double penaltyValue = Math.max(0d, support.readDoubleSetting(ruleNode.path("penalty_value"), 0d));
        if ("DEDUCT_POINTS".equalsIgnoreCase(action)) {
            return penaltyValue;
        }

        if ("DEDUCT_PERCENTAGE".equalsIgnoreCase(action)) {
            return Math.max(0d, tablePoints * penaltyValue / 100d);
        }

        return null;
    }
}
