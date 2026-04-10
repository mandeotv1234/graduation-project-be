package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.domain.models.TableMetadata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CreateTableRubricEvaluator {

    private CreateTableRubricEvaluator() {
    }

    static CreateTableRubricGradeResult evaluate(
            JsonNode rubric,
            List<TableMetadata> actualTables,
            BigDecimal totalPoints) {
        JsonNode payload = rubric.path("grading_payload");
        JsonNode settings = payload.path("grading_settings");
        JsonNode tables = payload.path("tables");
        JsonNode gradingRules = resolveCreateGradingRules(rubric, payload);

        boolean caseSensitive = settings.path("case_sensitive_names").asBoolean(false);
        boolean positiveOnlyScoring = settings.path("positive_only_scoring").asBoolean(false);
        boolean failAllMode = "FAIL_ALL".equalsIgnoreCase(
                settings.path("syntax_error_action").asText("PARTIAL"));
        boolean deductionMode = isDeductionMode(settings, tables);
        boolean skipChildChecksWhenTableMissing = deductionMode
                && settings.path("skip_child_checks_when_table_missing").asBoolean(true);

        BigDecimal earnedTotal = deductionMode ? totalPoints : BigDecimal.ZERO;
        BigDecimal totalDeductions = BigDecimal.ZERO;
        List<Map<String, Object>> details = new ArrayList<>();
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;
        boolean ruleFailAllTriggered = false;

        for (JsonNode rubricTable : tables) {
            String expectedName = rubricTable.path("expected_name").asText("");
            String missingAction = rubricTable.path("missing_penalty_action").asText("SKIP_TABLE");
            TableMetadata actualTable = findTable(actualTables, expectedName, caseSensitive);
            BigDecimal tableScoreCap = deductionMode
                    ? BigDecimal.valueOf(getTableDeductionCap(rubricTable))
                    : BigDecimal.ZERO;
            BigDecimal tableDeductionCap = deductionMode
                    ? BigDecimal.valueOf(getTableDeductionCap(rubricTable))
                    : BigDecimal.ZERO;
            BigDecimal tableDeductions = BigDecimal.ZERO;
            List<Map<String, Object>> tableDetails = new ArrayList<>();

            if (actualTable == null) {
                allPassed = false;
                if (deductionMode) {
                    double requestedPenalty = resolveMissingTablePenalty(
                            rubricTable,
                            missingAction,
                            skipChildChecksWhenTableMissing);
                    DeductionApplication deduction = applyTableDeduction(
                            tableDeductions,
                            tableDeductionCap,
                            requestedPenalty);
                    tableDeductions = deduction.updatedTotal();
                    double appliedPenalty = deduction.appliedPenalty();

                    String message = isFullTableLoss(missingAction, skipChildChecksWhenTableMissing)
                            ? String.format("Thieu bang %s, mat toan bo diem phan bang (-%s d).",
                            expectedName, formatPenalty(requestedPenalty))
                            : String.format("Thieu bang %s (-%s d).",
                            expectedName, formatPenalty(requestedPenalty));

                    appendIssue(errorBuilder, message);
                    tableDetails.add(Map.of(
                            "type", "error",
                            "message", buildCappedMessage("Thieu bang " + expectedName, requestedPenalty, appliedPenalty),
                            "points", -requestedPenalty));
                    totalDeductions = totalDeductions.add(tableDeductions);
                    details.add(buildTableSummary(expectedName, tableScoreCap, tableDeductions));
                    details.addAll(tableDetails);
                } else {
                    double lost = getMissingTablePenalty(rubricTable);
                    for (JsonNode rubricColumn : rubricTable.path("columns")) {
                        lost += getMissingColumnPenalty(rubricColumn);
                    }
                    for (JsonNode rubricConstraint : rubricTable.path("constraints")) {
                        lost += getMissingConstraintPenalty(rubricConstraint);
                    }

                    appendIssue(errorBuilder, String.format("Thieu bang %s.", expectedName));
                    details.add(Map.of(
                            "type", "error",
                            "message", "Thieu bang " + expectedName,
                            "points", positiveOnlyScoring ? 0 : -lost));
                }
                continue;
            }

            if (!deductionMode) {
                double existencePoints = getMissingTablePenalty(rubricTable);
                earnedTotal = earnedTotal.add(BigDecimal.valueOf(existencePoints));
                tableDetails.add(Map.of(
                        "type", "success",
                        "message", String.format("Bang %s ton tai", expectedName),
                        "points", existencePoints));
            }

            for (JsonNode rubricColumn : rubricTable.path("columns")) {
                String colName = rubricColumn.path("name").asText("");
                String expectedType = rubricColumn.path("expected_type").asText("");
                double colPoints = getMissingColumnPenalty(rubricColumn);
                double typePenalty = getTypeMismatchPenalty(rubricColumn);

                TableMetadata.ColumnMetadata actualColumn = findColumn(actualTable, colName, caseSensitive);
                if (actualColumn == null) {
                    allPassed = false;
                    if (deductionMode) {
                        double requestedPenalty = getMissingColumnPenalty(rubricColumn);
                        DeductionApplication deduction = applyTableDeduction(
                                tableDeductions,
                                tableDeductionCap,
                                requestedPenalty);
                        tableDeductions = deduction.updatedTotal();
                        double appliedPenalty = deduction.appliedPenalty();
                        String message = String.format("Bang %s: thieu cot %s (-%s d).",
                                expectedName, colName, formatPenalty(requestedPenalty));
                        appendIssue(errorBuilder, message);
                        tableDetails.add(Map.of(
                                "type", "error",
                                "message", buildCappedMessage(
                                        String.format("Bang %s: thieu cot %s", expectedName, colName),
                                        requestedPenalty,
                                        appliedPenalty),
                                "points", -requestedPenalty));
                    } else {
                        String message = positiveOnlyScoring
                                ? String.format("Bang %s: thieu cot %s (khong cong diem muc nay).", expectedName, colName)
                                : String.format("Bang %s: thieu cot %s (-%s d).",
                                expectedName, colName, formatPenalty(colPoints));
                        appendIssue(errorBuilder, message);
                        tableDetails.add(Map.of(
                                "type", "error",
                                "message", String.format("Bang %s: thieu cot %s", expectedName, colName),
                                "points", positiveOnlyScoring ? 0 : -colPoints));
                    }
                    continue;
                }

                boolean typeMatch = matchesSqlType(actualColumn.getRawDataType(), expectedType);
                if (typeMatch) {
                    if (!deductionMode) {
                        earnedTotal = earnedTotal.add(BigDecimal.valueOf(colPoints));
                        tableDetails.add(Map.of(
                                "type", "success",
                                "message", String.format("Bang %s: cot %s (%s) OK", expectedName, colName, expectedType),
                                "points", colPoints));
                    }
                    continue;
                }

                allPassed = false;
                if (deductionMode) {
                    double requestedPenalty = typePenalty;
                    DeductionApplication deduction = applyTableDeduction(
                            tableDeductions,
                            tableDeductionCap,
                            requestedPenalty);
                    tableDeductions = deduction.updatedTotal();
                    double appliedPenalty = deduction.appliedPenalty();
                    String message = String.format(
                            "Bang %s: cot %s sai kieu (ky vong: %s, thuc te: %s, -%s d).",
                            expectedName, colName, expectedType, actualColumn.getDataType(), formatPenalty(requestedPenalty));
                    appendIssue(errorBuilder, message);
                    tableDetails.add(Map.of(
                            "type", "warning",
                            "message", buildCappedMessage(
                                    String.format(
                                    "Bang %s: cot %s sai kieu (ky vong: %s, thuc te: %s)",
                                    expectedName, colName, expectedType, actualColumn.getDataType()),
                                    requestedPenalty,
                                    appliedPenalty),
                            "points", -requestedPenalty));
                } else {
                    double awarded = positiveOnlyScoring ? 0 : Math.max(0, colPoints - typePenalty);
                    earnedTotal = earnedTotal.add(BigDecimal.valueOf(awarded));
                    String message = positiveOnlyScoring
                            ? String.format(
                            "Bang %s: cot %s sai kieu (ky vong: %s, thuc te: %s, khong cong diem muc nay).",
                            expectedName, colName, expectedType, actualColumn.getDataType())
                            : String.format(
                            "Bang %s: cot %s sai kieu (ky vong: %s, thuc te: %s, -%s d).",
                            expectedName, colName, expectedType, actualColumn.getDataType(), formatPenalty(typePenalty));
                    appendIssue(errorBuilder, message);
                    tableDetails.add(Map.of(
                            "type", "warning",
                            "message", String.format(
                                    "Bang %s: cot %s sai kieu (ky vong: %s, thuc te: %s)",
                                    expectedName, colName, expectedType, actualColumn.getDataType()),
                            "points", positiveOnlyScoring ? 0 : -typePenalty));
                }
            }

            for (JsonNode rubricConstraint : rubricTable.path("constraints")) {
                String constraintType = rubricConstraint.path("type").asText("");
                double constraintPoints = getMissingConstraintPenalty(rubricConstraint);
                double constraintPenalty = getMissingConstraintPenalty(rubricConstraint);
                boolean constraintFound = doesConstraintMatch(actualTable, rubricConstraint, caseSensitive);
                String constraintLabel = buildConstraintLabel(constraintType, rubricConstraint.path("columns"));

                if (constraintFound) {
                    if (!deductionMode) {
                        earnedTotal = earnedTotal.add(BigDecimal.valueOf(constraintPoints));
                        tableDetails.add(Map.of(
                                "type", "success",
                                "message", String.format("Bang %s: rang buoc %s OK", expectedName, constraintLabel),
                                "points", constraintPoints));
                    }
                    continue;
                }

                allPassed = false;
                if (deductionMode) {
                    double requestedPenalty = constraintPenalty;
                    DeductionApplication deduction = applyTableDeduction(
                            tableDeductions,
                            tableDeductionCap,
                            requestedPenalty);
                    tableDeductions = deduction.updatedTotal();
                    double appliedPenalty = deduction.appliedPenalty();
                    String message = String.format("Bang %s: thieu rang buoc %s (-%s d).",
                            expectedName, constraintLabel, formatPenalty(requestedPenalty));
                    appendIssue(errorBuilder, message);
                    tableDetails.add(Map.of(
                            "type", "error",
                            "message", buildCappedMessage(
                                    String.format("Bang %s: thieu rang buoc %s", expectedName, constraintLabel),
                                    requestedPenalty,
                                    appliedPenalty),
                            "points", -requestedPenalty));
                } else {
                    double awarded = positiveOnlyScoring ? 0 : Math.max(0, constraintPoints - constraintPenalty);
                    earnedTotal = earnedTotal.add(BigDecimal.valueOf(awarded));
                    String message = positiveOnlyScoring
                            ? String.format("Bang %s: thieu rang buoc %s (khong cong diem muc nay).",
                            expectedName, constraintLabel)
                            : String.format("Bang %s: thieu rang buoc %s (-%s d).",
                            expectedName, constraintLabel, formatPenalty(constraintPenalty));
                    appendIssue(errorBuilder, message);
                    tableDetails.add(Map.of(
                            "type", "error",
                            "message", String.format("Bang %s: thieu rang buoc %s", expectedName, constraintLabel),
                            "points", positiveOnlyScoring ? 0 : -constraintPenalty));
                }
            }

            if (deductionMode) {
                totalDeductions = totalDeductions.add(tableDeductions);
                details.add(buildTableSummary(expectedName, tableScoreCap, tableDeductions));
                details.addAll(tableDetails);
            } else {
                details.addAll(tableDetails);
            }
        }

        if (deductionMode) {
            earnedTotal = totalPoints.subtract(totalDeductions);
        }

        CreateRuleAdjustment ruleAdjustment = applyCreateRuleAdjustments(
                gradingRules,
                tables,
                actualTables,
                caseSensitive,
                totalPoints);
        if (ruleAdjustment.hasViolations()) {
            allPassed = false;
        }
        if (ruleAdjustment.failAllTriggered()) {
            ruleFailAllTriggered = true;
        }
        if (ruleAdjustment.totalPenalty().compareTo(BigDecimal.ZERO) > 0) {
            earnedTotal = earnedTotal.subtract(ruleAdjustment.totalPenalty());
            totalDeductions = totalDeductions.add(ruleAdjustment.totalPenalty());
        }
        if (ruleAdjustment.errorMessage() != null && !ruleAdjustment.errorMessage().isBlank()) {
            appendIssue(errorBuilder, ruleAdjustment.errorMessage());
        }
        if (!ruleAdjustment.details().isEmpty()) {
            details.addAll(ruleAdjustment.details());
        }

        earnedTotal = earnedTotal.setScale(2, RoundingMode.HALF_UP);
        totalDeductions = totalDeductions.setScale(2, RoundingMode.HALF_UP);
        if ((failAllMode && !allPassed) || ruleFailAllTriggered) {
            earnedTotal = BigDecimal.ZERO;
            String failMessage = "Rubric dang de FAIL_ALL: co loi nen cau nay bi 0 diem toan bo.";
            appendIssue(errorBuilder, failMessage);
            details.add(Map.of(
                    "type", "warning",
                    "message", "Rubric dang de FAIL_ALL: co loi nen cau nay bi 0 diem toan bo",
                    "points", 0));
        }

        if (earnedTotal.compareTo(totalPoints) > 0) {
            earnedTotal = totalPoints;
        }
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0) {
            earnedTotal = BigDecimal.ZERO;
        }

        return new CreateTableRubricGradeResult(
                earnedTotal,
                totalDeductions,
                allPassed,
                errorBuilder.length() == 0 ? null : errorBuilder.toString().trim(),
                List.copyOf(details));
    }

    static boolean isDeductionMode(JsonNode settings, JsonNode tables) {
        if (settings.path("deduction_mode").asBoolean(false)) {
            return true;
        }

        if (!tables.isArray()) {
            return false;
        }

        for (JsonNode rubricTable : tables) {
            if (rubricTable.has("missing_table_penalty")) {
                return true;
            }

            for (JsonNode rubricColumn : rubricTable.path("columns")) {
                if (rubricColumn.has("missing_column_penalty")) {
                    return true;
                }
            }

            for (JsonNode rubricConstraint : rubricTable.path("constraints")) {
                if (rubricConstraint.has("missing_constraint_penalty")) {
                    return true;
                }
            }
        }

        return false;
    }

    private static JsonNode resolveCreateGradingRules(JsonNode rubric, JsonNode payload) {
        JsonNode[] candidates = new JsonNode[] {
                payload.path("grading_rules"),
                rubric.path("grading_rules")
        };

        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }

        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    private static CreateRuleAdjustment applyCreateRuleAdjustments(
            JsonNode gradingRules,
            JsonNode rubricTables,
            List<TableMetadata> actualTables,
            boolean caseSensitive,
            BigDecimal totalPoints) {
        if (gradingRules == null || !gradingRules.isArray() || gradingRules.isEmpty()) {
            return CreateRuleAdjustment.empty();
        }

        Map<String, Integer> violations = collectCreateRuleViolations(
                rubricTables,
                actualTables,
                caseSensitive);

        BigDecimal totalPenalty = BigDecimal.ZERO;
        boolean hasViolations = false;
        boolean failAllTriggered = false;
        StringBuilder issueBuilder = new StringBuilder();
        List<Map<String, Object>> details = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : violations.entrySet()) {
            int violationCount = entry.getValue() == null ? 0 : entry.getValue();
            if (violationCount <= 0) {
                continue;
            }

            String[] segments = entry.getKey().split("\\|", 2);
            if (segments.length != 2) {
                continue;
            }

            String target = segments[0];
            String condition = segments[1];
            JsonNode ruleNode = findCreateRule(gradingRules, target, condition);
            if (ruleNode == null) {
                continue;
            }

            double defaultPenaltyPerViolation = resolveCreateDefaultPenaltyPerViolation(
                    target,
                    rubricTables,
                    totalPoints,
                    violationCount);
            CreateRuleDecision decision = resolveCreateRuleDecision(
                    ruleNode,
                    totalPoints,
                    defaultPenaltyPerViolation);

            String ruleLabel = target + "/" + condition;
            String violationSummary = buildCreateViolationSummary(target, condition, violationCount);

            if (decision.ignore()) {
                details.add(Map.of(
                        "type", "info",
                        "message", "Rule " + ruleLabel + " bo qua vi pham (" + violationSummary + ")",
                        "points", 0));
                continue;
            }

            hasViolations = true;

            if (decision.failAll()) {
                failAllTriggered = true;
                appendIssue(issueBuilder,
                        "Rule " + ruleLabel + " kich hoat FAIL_ALL (" + violationSummary + ").");
                details.add(Map.of(
                        "type", "warning",
                        "message", "Rule " + ruleLabel + " kich hoat FAIL_ALL",
                        "points", 0));
                continue;
            }

            BigDecimal deduction = BigDecimal.valueOf(Math.max(0d, decision.penaltyPerViolation()))
                    .multiply(BigDecimal.valueOf(violationCount));
            if (deduction.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            totalPenalty = totalPenalty.add(deduction);
            String formattedDeduction = deduction
                    .setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString();
            appendIssue(issueBuilder,
                    "Rule " + ruleLabel + " (" + violationSummary + "): tru " + formattedDeduction + " diem.");
            details.add(Map.of(
                    "type", "warning",
                    "message", "Rule " + ruleLabel + " (" + violationSummary + ")",
                    "points", -deduction.setScale(2, RoundingMode.HALF_UP).doubleValue()));
        }

        return new CreateRuleAdjustment(
                totalPenalty,
                hasViolations,
                failAllTriggered,
                issueBuilder.length() == 0 ? null : issueBuilder.toString().trim(),
                List.copyOf(details));
    }

    private static Map<String, Integer> collectCreateRuleViolations(
            JsonNode rubricTables,
            List<TableMetadata> actualTables,
            boolean caseSensitive) {
        Map<String, Integer> violations = new LinkedHashMap<>();
        Set<String> expectedTableNames = new HashSet<>();

        if (rubricTables != null && rubricTables.isArray()) {
            for (JsonNode rubricTable : rubricTables) {
                String expectedName = rubricTable.path("expected_name").asText("");
                if (expectedName.isBlank()) {
                    continue;
                }

                String expectedTableKey = normalizeCreateIdentifier(expectedName, caseSensitive);
                expectedTableNames.add(expectedTableKey);

                TableMetadata actualTable = findTable(actualTables, expectedName, caseSensitive);
                if (actualTable == null) {
                    incrementViolationCount(violations, "TABLE", "IS_MISSING", 1);
                    continue;
                }

                Map<String, JsonNode> expectedColumnsByName = new LinkedHashMap<>();
                List<String> expectedColumnOrder = new ArrayList<>();
                for (JsonNode rubricColumn : rubricTable.path("columns")) {
                    String columnName = rubricColumn.path("name").asText("");
                    if (columnName.isBlank()) {
                        continue;
                    }
                    String columnKey = normalizeCreateIdentifier(columnName, caseSensitive);
                    expectedColumnsByName.put(columnKey, rubricColumn);
                    expectedColumnOrder.add(columnKey);
                }

                Map<String, TableMetadata.ColumnMetadata> actualColumnsByName = new LinkedHashMap<>();
                List<String> actualColumnOrder = new ArrayList<>();
                for (TableMetadata.ColumnMetadata actualColumn : actualTable.getColumns()) {
                    String actualColumnKey = normalizeCreateIdentifier(actualColumn.getColumnName(), caseSensitive);
                    actualColumnsByName.put(actualColumnKey, actualColumn);
                    actualColumnOrder.add(actualColumnKey);
                }

                for (Map.Entry<String, JsonNode> expectedColumn : expectedColumnsByName.entrySet()) {
                    String expectedColumnKey = expectedColumn.getKey();
                    JsonNode rubricColumn = expectedColumn.getValue();

                    TableMetadata.ColumnMetadata actualColumn = actualColumnsByName.get(expectedColumnKey);
                    if (actualColumn == null) {
                        incrementViolationCount(violations, "COLUMN", "IS_MISSING", 1);
                        continue;
                    }

                    String expectedType = rubricColumn.path("expected_type").asText("");
                    if (!expectedType.isBlank() && !matchesSqlType(actualColumn.getRawDataType(), expectedType)) {
                        incrementViolationCount(violations, "DATA_TYPE", "TYPE_MISMATCH", 1);
                    }
                }

                for (String actualColumnKey : actualColumnsByName.keySet()) {
                    if (!expectedColumnsByName.containsKey(actualColumnKey)) {
                        incrementViolationCount(violations, "COLUMN", "IS_EXTRA", 1);
                    }
                }

                int columnOrderViolations = countCreateColumnOrderViolations(
                        expectedColumnOrder,
                        actualColumnOrder);
                if (columnOrderViolations > 0) {
                    incrementViolationCount(violations, "COLUMN_ORDER", "OUT_OF_ORDER", columnOrderViolations);
                }

                Set<String> expectedPrimaryKeys = new HashSet<>();
                Set<String> expectedForeignKeys = new HashSet<>();

                for (JsonNode rubricConstraint : rubricTable.path("constraints")) {
                    String constraintType = rubricConstraint.path("type").asText("");
                    if (!doesConstraintMatch(actualTable, rubricConstraint, caseSensitive)) {
                        incrementViolationCount(violations, "CONSTRAINT_LOCAL", "IS_MISSING", 1);
                    }

                    JsonNode columnsNode = rubricConstraint.path("columns");
                    if (!columnsNode.isArray()) {
                        continue;
                    }

                    if ("PRIMARY_KEY".equalsIgnoreCase(constraintType)) {
                        for (JsonNode columnNode : columnsNode) {
                            String columnName = columnNode.asText("");
                            if (!columnName.isBlank()) {
                                expectedPrimaryKeys.add(normalizeCreateIdentifier(columnName, caseSensitive));
                            }
                        }
                        continue;
                    }

                    if (!"FOREIGN_KEY".equalsIgnoreCase(constraintType)) {
                        continue;
                    }

                    String referencesTable = rubricConstraint.path("references_table").asText("");
                    JsonNode referencesColumns = rubricConstraint.path("references_columns");

                    for (int i = 0; i < columnsNode.size(); i++) {
                        String fkColumn = columnsNode.get(i).asText("");
                        if (fkColumn.isBlank()) {
                            continue;
                        }

                        String fkColumnKey = normalizeCreateIdentifier(fkColumn, caseSensitive);
                        expectedForeignKeys.add(fkColumnKey);

                        TableMetadata.ColumnMetadata actualColumn = actualColumnsByName.get(fkColumnKey);
                        if (actualColumn == null || !actualColumn.isForeignKey()) {
                            incrementViolationCount(violations, "FOREIGN_KEY", "IS_MISSING", 1);
                            continue;
                        }

                        String expectedRefColumn = "";
                        if (referencesColumns.isArray() && referencesColumns.size() > i) {
                            expectedRefColumn = referencesColumns.get(i).asText("");
                        }

                        boolean tableMatched = matchesOptional(
                                actualColumn.getReferencesTable(),
                                referencesTable,
                                caseSensitive);
                        boolean columnMatched = matchesOptional(
                                actualColumn.getReferencesColumn(),
                                expectedRefColumn,
                                caseSensitive);
                        if (!tableMatched || !columnMatched) {
                            incrementViolationCount(violations, "FOREIGN_KEY", "REFERENCE_ERROR", 1);
                        }
                    }
                }

                for (String expectedPrimaryKey : expectedPrimaryKeys) {
                    TableMetadata.ColumnMetadata actualColumn = actualColumnsByName.get(expectedPrimaryKey);
                    if (actualColumn == null || !actualColumn.isPrimaryKey()) {
                        incrementViolationCount(violations, "PRIMARY_KEY", "IS_MISSING", 1);
                    }
                }

                int extraPrimaryKeys = 0;
                int extraForeignKeys = 0;
                for (Map.Entry<String, TableMetadata.ColumnMetadata> actualColumnEntry : actualColumnsByName.entrySet()) {
                    String actualColumnKey = actualColumnEntry.getKey();
                    TableMetadata.ColumnMetadata actualColumn = actualColumnEntry.getValue();

                    if (actualColumn.isPrimaryKey() && !expectedPrimaryKeys.contains(actualColumnKey)) {
                        extraPrimaryKeys++;
                    }
                    if (actualColumn.isForeignKey() && !expectedForeignKeys.contains(actualColumnKey)) {
                        extraForeignKeys++;
                    }
                }

                if (extraPrimaryKeys > 0) {
                    incrementViolationCount(violations, "PRIMARY_KEY", "IS_EXTRA", extraPrimaryKeys);
                }
                if (extraForeignKeys > 0) {
                    incrementViolationCount(violations, "FOREIGN_KEY", "IS_EXTRA", extraForeignKeys);
                }
                if (extraPrimaryKeys + extraForeignKeys > 0) {
                    incrementViolationCount(violations, "CONSTRAINT_LOCAL", "IS_EXTRA", extraPrimaryKeys + extraForeignKeys);
                }
            }
        }

        if (actualTables != null) {
            for (TableMetadata actualTable : actualTables) {
                String actualTableName = normalizeCreateIdentifier(actualTable.getTableName(), caseSensitive);
                if (!expectedTableNames.contains(actualTableName)) {
                    incrementViolationCount(violations, "TABLE", "IS_EXTRA", 1);
                }
            }
        }

        return violations;
    }

    private static int countCreateColumnOrderViolations(
            List<String> expectedColumnOrder,
            List<String> actualColumnOrder) {
        if (expectedColumnOrder == null || expectedColumnOrder.isEmpty()
                || actualColumnOrder == null || actualColumnOrder.isEmpty()) {
            return 0;
        }

        Set<String> expectedSet = new HashSet<>(expectedColumnOrder);
        List<String> actualFiltered = new ArrayList<>();
        for (String actualColumn : actualColumnOrder) {
            if (expectedSet.contains(actualColumn)) {
                actualFiltered.add(actualColumn);
            }
        }

        int limit = Math.min(expectedColumnOrder.size(), actualFiltered.size());
        int mismatches = 0;
        for (int i = 0; i < limit; i++) {
            if (!expectedColumnOrder.get(i).equals(actualFiltered.get(i))) {
                mismatches++;
            }
        }
        return mismatches;
    }

    private static JsonNode findCreateRule(JsonNode gradingRules, String target, String condition) {
        if (gradingRules == null || !gradingRules.isArray()) {
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

    private static CreateRuleDecision resolveCreateRuleDecision(
            JsonNode ruleNode,
            BigDecimal totalPoints,
            double defaultPenaltyPerViolation) {
        String action = ruleNode != null ? ruleNode.path("action").asText("").trim() : "";
        if (action.isBlank()) {
            action = "DEDUCT_POINTS";
        }

        String normalizedAction = action.toUpperCase(Locale.ROOT);
        double safeDefaultPenalty = Math.max(0d, defaultPenaltyPerViolation);
        double penaltyValue = readDoubleRuleValue(ruleNode != null ? ruleNode.path("penalty_value") : null, -1d);

        switch (normalizedAction) {
            case "IGNORE":
                return new CreateRuleDecision(normalizedAction, 0d, true, false);
            case "FAIL_ALL":
                return new CreateRuleDecision(normalizedAction, 0d, false, true);
            case "FAIL_ITEM":
                return new CreateRuleDecision(normalizedAction, safeDefaultPenalty, false, false);
            case "DEDUCT_PERCENTAGE": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, totalPoints.doubleValue() * penaltyValue / 100d)
                        : safeDefaultPenalty;
                return new CreateRuleDecision(normalizedAction, penalty, false, false);
            }
            case "DEDUCT_POINTS": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, penaltyValue)
                        : safeDefaultPenalty;
                return new CreateRuleDecision(normalizedAction, penalty, false, false);
            }
            default:
                return new CreateRuleDecision("DEDUCT_POINTS", safeDefaultPenalty, false, false);
        }
    }

    private static double resolveCreateDefaultPenaltyPerViolation(
            String target,
            JsonNode rubricTables,
            BigDecimal totalPoints,
            int violationCount) {
        if (totalPoints == null || totalPoints.compareTo(BigDecimal.ZERO) <= 0) {
            return 0d;
        }

        int expectedItems = resolveExpectedCreateItemCount(target, rubricTables);
        int divisor = Math.max(1, expectedItems > 0 ? expectedItems : violationCount);
        return totalPoints
                .divide(BigDecimal.valueOf(divisor), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static int resolveExpectedCreateItemCount(String target, JsonNode rubricTables) {
        int tableCount = 0;
        int columnCount = 0;
        int constraintCount = 0;
        int primaryKeyCount = 0;
        int foreignKeyCount = 0;

        if (rubricTables != null && rubricTables.isArray()) {
            for (JsonNode rubricTable : rubricTables) {
                tableCount++;

                JsonNode columns = rubricTable.path("columns");
                if (columns.isArray()) {
                    columnCount += columns.size();
                }

                JsonNode constraints = rubricTable.path("constraints");
                if (!constraints.isArray()) {
                    continue;
                }

                for (JsonNode rubricConstraint : constraints) {
                    constraintCount++;
                    String type = rubricConstraint.path("type").asText("");
                    JsonNode constraintColumns = rubricConstraint.path("columns");
                    int columnSize = constraintColumns.isArray() && constraintColumns.size() > 0
                            ? constraintColumns.size()
                            : 1;
                    if ("PRIMARY_KEY".equalsIgnoreCase(type)) {
                        primaryKeyCount += columnSize;
                    }
                    if ("FOREIGN_KEY".equalsIgnoreCase(type)) {
                        foreignKeyCount += columnSize;
                    }
                }
            }
        }

        switch (target.toUpperCase(Locale.ROOT)) {
            case "TABLE":
                return Math.max(1, tableCount);
            case "COLUMN":
            case "DATA_TYPE":
            case "COLUMN_ORDER":
                return Math.max(1, columnCount);
            case "PRIMARY_KEY":
                return Math.max(1, primaryKeyCount);
            case "FOREIGN_KEY":
                return Math.max(1, foreignKeyCount);
            case "CONSTRAINT_LOCAL":
                return Math.max(1, constraintCount);
            default:
                return Math.max(1, columnCount);
        }
    }

    private static String buildCreateViolationSummary(String target, String condition, int count) {
        return target + "/" + condition + " x" + count;
    }

    private static void incrementViolationCount(
            Map<String, Integer> violations,
            String target,
            String condition,
            int delta) {
        if (delta <= 0) {
            return;
        }
        String key = target.toUpperCase(Locale.ROOT) + "|" + condition.toUpperCase(Locale.ROOT);
        violations.merge(key, delta, Integer::sum);
    }

    private static String normalizeCreateIdentifier(String value, boolean caseSensitive) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return caseSensitive ? normalized : normalized.toLowerCase(Locale.ROOT);
    }

    private static double readDoubleRuleValue(JsonNode node, double defaultValue) {
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

    private static TableMetadata findTable(List<TableMetadata> actualTables, String expectedName, boolean caseSensitive) {
        if (actualTables == null || actualTables.isEmpty()) {
            return null;
        }
        return actualTables.stream()
                .filter(table -> caseSensitive
                        ? table.getTableName().equals(expectedName)
                        : table.getTableName().equalsIgnoreCase(expectedName))
                .findFirst()
                .orElse(null);
    }

    private static TableMetadata.ColumnMetadata findColumn(
            TableMetadata actualTable,
            String columnName,
            boolean caseSensitive) {
        return actualTable.getColumns().stream()
                .filter(column -> caseSensitive
                        ? column.getColumnName().equals(columnName)
                        : column.getColumnName().equalsIgnoreCase(columnName))
                .findFirst()
                .orElse(null);
    }

    private static double resolveMissingTablePenalty(
            JsonNode rubricTable,
            String missingAction,
            boolean skipChildChecksWhenTableMissing) {
        if (isFullTableLoss(missingAction, skipChildChecksWhenTableMissing)) {
            return calculateTableBudget(rubricTable);
        }
        return getMissingTablePenalty(rubricTable);
    }

    private static boolean isFullTableLoss(String missingAction, boolean skipChildChecksWhenTableMissing) {
        if ("ZERO_POINTS".equalsIgnoreCase(missingAction)) {
            return true;
        }
        return !skipChildChecksWhenTableMissing && !"SKIP_TABLE".equalsIgnoreCase(missingAction);
    }

    private static double getTableDeductionCap(JsonNode rubricTable) {
        return Math.max(0, getMissingTablePenalty(rubricTable));
    }

    private static double calculateTableBudget(JsonNode rubricTable) {
        double total = getMissingTablePenalty(rubricTable);
        for (JsonNode rubricColumn : rubricTable.path("columns")) {
            total += getMissingColumnPenalty(rubricColumn);
        }
        for (JsonNode rubricConstraint : rubricTable.path("constraints")) {
            total += getMissingConstraintPenalty(rubricConstraint);
        }
        return total;
    }

    private static double getMissingTablePenalty(JsonNode rubricTable) {
        return rubricTable.path("missing_table_penalty").asDouble(0);
    }

    private static double getMissingColumnPenalty(JsonNode rubricColumn) {
        return rubricColumn.path("missing_column_penalty").asDouble(0);
    }

    private static double getTypeMismatchPenalty(JsonNode rubricColumn) {
        return rubricColumn.path("type_mismatch_penalty").asDouble(0);
    }

    private static double getMissingConstraintPenalty(JsonNode rubricConstraint) {
        return rubricConstraint.path("missing_constraint_penalty").asDouble(0);
    }

    private static boolean doesConstraintMatch(
            TableMetadata actualTable,
            JsonNode rubricConstraint,
            boolean caseSensitive) {
        String constraintType = rubricConstraint.path("type").asText("");
        JsonNode columns = rubricConstraint.path("columns");
        if (!columns.isArray() || columns.size() == 0) {
            return false;
        }

        if ("PRIMARY_KEY".equals(constraintType)) {
            for (JsonNode columnNode : columns) {
                String pkColumn = columnNode.asText("");
                boolean matched = actualTable.getColumns().stream()
                        .anyMatch(column -> matches(column.getColumnName(), pkColumn, caseSensitive)
                                && column.isPrimaryKey());
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        if ("FOREIGN_KEY".equals(constraintType)) {
            String referencesTable = rubricConstraint.path("references_table").asText("");
            JsonNode referencesColumns = rubricConstraint.path("references_columns");
            boolean validateReferencedColumns = referencesColumns.isArray()
                    && referencesColumns.size() == columns.size()
                    && referencesColumns.size() > 0;

            for (int i = 0; i < columns.size(); i++) {
                String fkColumn = columns.get(i).asText("");
                String referencedColumn = validateReferencedColumns ? referencesColumns.get(i).asText("") : "";

                boolean matched = actualTable.getColumns().stream()
                        .anyMatch(column -> matches(column.getColumnName(), fkColumn, caseSensitive)
                                && column.isForeignKey()
                                && matchesOptional(column.getReferencesTable(), referencesTable, caseSensitive)
                                && matchesOptional(column.getReferencesColumn(), referencedColumn, caseSensitive));
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        return true;
    }

    private static String buildConstraintLabel(String constraintType, JsonNode columns) {
        String columnList = buildColumnList(columns);
        if (columnList.isBlank()) {
            return constraintType;
        }
        return constraintType + " [" + columnList + "]";
    }

    private static String buildColumnList(JsonNode columns) {
        if (!columns.isArray() || columns.size() == 0) {
            return "";
        }

        List<String> names = new ArrayList<>();
        for (JsonNode column : columns) {
            String value = column.asText("");
            if (!value.isBlank()) {
                names.add(value);
            }
        }
        return String.join(", ", names);
    }

    private static boolean matches(String left, String right, boolean caseSensitive) {
        if (left == null || right == null) {
            return false;
        }
        return caseSensitive ? left.equals(right) : left.equalsIgnoreCase(right);
    }

    private static boolean matchesOptional(String actualValue, String expectedValue, boolean caseSensitive) {
        if (expectedValue == null || expectedValue.isBlank()) {
            return true;
        }
        if (actualValue == null || actualValue.isBlank()) {
            return false;
        }
        return matches(actualValue, expectedValue, caseSensitive);
    }

    private static boolean matchesSqlType(String actualType, String expectedType) {
        return normalizeSqlType(actualType).equals(normalizeSqlType(expectedType));
    }

    private static String normalizeSqlType(String sqlType) {
        if (sqlType == null) {
            return "";
        }
        String normalized = sqlType.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }

        int parenIndex = normalized.indexOf('(');
        if (parenIndex >= 0) {
            normalized = normalized.substring(0, parenIndex);
        }

        int spaceIndex = normalized.indexOf(' ');
        if (spaceIndex >= 0) {
            normalized = normalized.substring(0, spaceIndex);
        }

        return normalized.trim();
    }

    private static void appendIssue(StringBuilder errorBuilder, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (errorBuilder.length() > 0) {
            errorBuilder.append(' ');
        }
        errorBuilder.append(message.trim());
    }

    private static String formatPenalty(double penalty) {
        return BigDecimal.valueOf(penalty)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static Map<String, Object> buildTableSummary(
            String tableName,
            BigDecimal tableBudget,
            BigDecimal tableDeductions) {
        BigDecimal earned = tableBudget.subtract(tableDeductions).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return Map.of(
                "type", "info",
                "message", String.format("Bang %s: %s diem", tableName, earned.stripTrailingZeros().toPlainString()),
                "points", earned.doubleValue());
    }

    private static String buildCappedMessage(String baseMessage, double requestedPenalty, double appliedPenalty) {
        if (requestedPenalty > 0 && appliedPenalty < requestedPenalty) {
            return baseMessage + " (bang da ve 0 diem)";
        }
        return baseMessage;
    }

    private static DeductionApplication applyTableDeduction(
            BigDecimal currentTableDeductions,
            BigDecimal tableDeductionCap,
            double requestedPenalty) {
        if (requestedPenalty <= 0) {
            return new DeductionApplication(0, currentTableDeductions);
        }

        BigDecimal remainingCap = tableDeductionCap.subtract(currentTableDeductions);
        if (remainingCap.compareTo(BigDecimal.ZERO) <= 0) {
            return new DeductionApplication(0, currentTableDeductions);
        }

        BigDecimal applied = BigDecimal.valueOf(requestedPenalty).min(remainingCap);
        return new DeductionApplication(
                applied.doubleValue(),
                currentTableDeductions.add(applied));
    }

    private record CreateRuleDecision(
            String action,
            double penaltyPerViolation,
            boolean ignore,
            boolean failAll) {
    }

    private record CreateRuleAdjustment(
            BigDecimal totalPenalty,
            boolean hasViolations,
            boolean failAllTriggered,
            String errorMessage,
            List<Map<String, Object>> details) {
        static CreateRuleAdjustment empty() {
            return new CreateRuleAdjustment(
                    BigDecimal.ZERO,
                    false,
                    false,
                    null,
                    List.of());
        }
    }

    record CreateTableRubricGradeResult(
            BigDecimal earnedPoints,
            BigDecimal totalDeductions,
            boolean allPassed,
            String errorMessage,
            List<Map<String, Object>> details) {
    }

    private record DeductionApplication(double appliedPenalty, BigDecimal updatedTotal) {
    }
}
