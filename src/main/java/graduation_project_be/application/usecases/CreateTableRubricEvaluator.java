package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.domain.models.TableMetadata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

        earnedTotal = earnedTotal.setScale(2, RoundingMode.HALF_UP);
        totalDeductions = totalDeductions.setScale(2, RoundingMode.HALF_UP);
        if (failAllMode && !allPassed) {
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

    private static TableMetadata findTable(List<TableMetadata> actualTables, String expectedName, boolean caseSensitive) {
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
