package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.application.usecases.grading.createtable.CreateSchemaEdit;
import graduation_project_be.application.usecases.grading.createtable.CreateSchemaGraph;
import graduation_project_be.application.usecases.grading.createtable.CreateSchemaGraphBuilder;
import graduation_project_be.application.usecases.grading.createtable.CreateSchemaGraphComparator;
import graduation_project_be.application.usecases.grading.createtable.CreateWeightedEditScorer;
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

public final class CreateTableRubricEvaluator {

    private static final String GLOBAL_SCOPE = "__GLOBAL__";

    private CreateTableRubricEvaluator() {
    }

    public static CreateTableRubricGradeResult evaluate(
            JsonNode rubric,
            List<TableMetadata> actualTables,
            BigDecimal totalPoints) {
        JsonNode payload = rubric.path("grading_payload");
        JsonNode settings = payload.path("grading_settings");
        JsonNode tables = payload.path("tables");
        JsonNode gradingRules = resolveCreateGradingRules(rubric, payload);

        boolean caseSensitive = settings.path("case_sensitive_names").asBoolean(false);
        CreateSchemaGraph expectedGraph = CreateSchemaGraphBuilder.fromRubric(tables, caseSensitive);
        CreateSchemaGraph actualGraph = CreateSchemaGraphBuilder.fromMetadata(actualTables, caseSensitive);
        List<CreateSchemaEdit> edits = CreateSchemaGraphComparator.compare(expectedGraph, actualGraph, caseSensitive);
        CreateWeightedEditScorer.CreateScoringResult result = CreateWeightedEditScorer.score(
                edits,
                tables,
                gradingRules,
                totalPoints,
                caseSensitive);

        return new CreateTableRubricGradeResult(
                result.earnedPoints(),
                result.totalDeductions(),
                result.allPassed(),
                result.errorMessage(),
                result.details());
    }

    static boolean isDeductionMode(JsonNode settings, JsonNode tables, JsonNode gradingRules) {
        if (settings.path("deduction_mode").asBoolean(false)) {
            return true;
        }

        if (gradingRules != null && gradingRules.isArray() && gradingRules.size() > 0) {
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
        Map<String, BigDecimal> tableBudgets = resolveCreateRuleTableBudgets(rubricTables, totalPoints);
        Map<String, BigDecimal> tableDeductions = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : violations.entrySet()) {
            int violationCount = entry.getValue() == null ? 0 : entry.getValue();
            if (violationCount <= 0) {
                continue;
            }

            String[] segments = entry.getKey().split("\\|", 3);
            if (segments.length < 2) {
                continue;
            }

            String target = segments[0];
            String condition = segments[1];
            String scope = segments.length >= 3 ? segments[2] : GLOBAL_SCOPE;
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

            String violationTitle = buildCreateViolationTitle(target, condition);
            String scopeLabel = GLOBAL_SCOPE.equals(scope) || scope.isBlank()
                    ? ""
                    : "Bảng " + scope + ": ";
            String scopeDetail = buildCreateRuleScopeDetail(
                    target,
                    condition,
                    scope,
                    rubricTables,
                    actualTables,
                    caseSensitive);
            String userMessage = (scopeDetail == null || scopeDetail.isBlank())
                    ? scopeLabel + violationTitle + " (" + violationCount + " lỗi)"
                    : scopeLabel + scopeDetail;

            if (decision.ignore()) {
                details.add(Map.of(
                        "type", "info",
                        "message", userMessage + " (bỏ qua theo cấu hình)",
                        "points", 0));
                continue;
            }

            hasViolations = true;

            if (decision.failAll()) {
                failAllTriggered = true;
                appendIssue(issueBuilder,
                        userMessage + ": kích hoạt FAIL_ALL.");
                details.add(Map.of(
                        "type", "warning",
                        "message", userMessage + " (kích hoạt FAIL_ALL)",
                        "points", 0));
                continue;
            }

            BigDecimal requestedDeduction = BigDecimal.valueOf(Math.max(0d, decision.penaltyPerViolation()))
                    .multiply(BigDecimal.valueOf(violationCount));
            if (requestedDeduction.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal appliedDeduction = applyCreateScopeCap(
                    scope,
                    requestedDeduction,
                    tableBudgets,
                    tableDeductions);
            if (appliedDeduction.compareTo(BigDecimal.ZERO) <= 0) {
                details.add(Map.of(
                        "type", "info",
                        "message", scopeLabel + "Đã đạt mức trừ tối đa của bảng, không trừ thêm.",
                        "points", 0));
                continue;
            }

            totalPenalty = totalPenalty.add(appliedDeduction);
            String formattedDeduction = appliedDeduction
                    .setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString();
            appendIssue(issueBuilder,
                    userMessage + ": trừ " + formattedDeduction + " điểm.");
            details.add(Map.of(
                    "type", "warning",
                    "message", userMessage,
                    "points", -appliedDeduction.setScale(2, RoundingMode.HALF_UP).doubleValue()));
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
                    incrementViolationCount(violations, "TABLE", "IS_MISSING", 1, expectedName);
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
                        incrementViolationCount(violations, "COLUMN", "IS_MISSING", 1, expectedName);
                        continue;
                    }

                    String expectedType = rubricColumn.path("expected_type").asText("");
                    if (!expectedType.isBlank() && !matchesSqlType(actualColumn.getRawDataType(), expectedType)) {
                        incrementViolationCount(violations, "DATA_TYPE", "TYPE_MISMATCH", 1, expectedName);
                    }
                }

                for (String actualColumnKey : actualColumnsByName.keySet()) {
                    if (!expectedColumnsByName.containsKey(actualColumnKey)) {
                        incrementViolationCount(violations, "COLUMN", "IS_EXTRA", 1, expectedName);
                    }
                }

                int columnOrderViolations = countCreateColumnOrderViolations(
                        expectedColumnOrder,
                        actualColumnOrder);
                if (columnOrderViolations > 0) {
                    incrementViolationCount(violations, "COLUMN_ORDER", "OUT_OF_ORDER", columnOrderViolations, expectedName);
                }

                Set<String> expectedPrimaryKeys = new HashSet<>();
                List<JsonNode> expectedForeignKeyConstraints = new ArrayList<>();

                for (JsonNode rubricConstraint : rubricTable.path("constraints")) {
                    String constraintType = rubricConstraint.path("type").asText("");
                    if (!doesConstraintMatch(actualTable, rubricConstraint, caseSensitive)) {
                        incrementViolationCount(violations, "CONSTRAINT_LOCAL", "IS_MISSING", 1, expectedName);
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

                    expectedForeignKeyConstraints.add(rubricConstraint);
                    List<TableMetadata.ForeignKeyMetadata> actualForeignKeys = getActualForeignKeys(actualTable);
                    boolean localColumnsMatched = actualForeignKeys.stream()
                            .anyMatch(foreignKey -> foreignKeyLocalColumnsMatch(
                                    foreignKey, columnsNode, caseSensitive));
                    if (!localColumnsMatched) {
                        incrementViolationCount(violations, "FOREIGN_KEY", "IS_MISSING", 1, expectedName);
                    } else if (actualForeignKeys.stream().noneMatch(foreignKey ->
                            foreignKeyMatches(foreignKey, rubricConstraint, caseSensitive))) {
                        incrementViolationCount(violations, "FOREIGN_KEY", "REFERENCE_ERROR", 1, expectedName);
                    }
                }

                for (String expectedPrimaryKey : expectedPrimaryKeys) {
                    TableMetadata.ColumnMetadata actualColumn = actualColumnsByName.get(expectedPrimaryKey);
                    if (actualColumn == null || !actualColumn.isPrimaryKey()) {
                        incrementViolationCount(violations, "PRIMARY_KEY", "IS_MISSING", 1, expectedName);
                    }
                }

                int extraPrimaryKeys = 0;
                for (Map.Entry<String, TableMetadata.ColumnMetadata> actualColumnEntry : actualColumnsByName.entrySet()) {
                    String actualColumnKey = actualColumnEntry.getKey();
                    TableMetadata.ColumnMetadata actualColumn = actualColumnEntry.getValue();

                    if (actualColumn.isPrimaryKey() && !expectedPrimaryKeys.contains(actualColumnKey)) {
                        extraPrimaryKeys++;
                    }
                }

                int extraForeignKeys = 0;
                for (TableMetadata.ForeignKeyMetadata actualForeignKey : getActualForeignKeys(actualTable)) {
                    boolean expected = expectedForeignKeyConstraints.stream()
                            .anyMatch(constraint -> foreignKeyMatches(actualForeignKey, constraint, caseSensitive));
                    if (!expected) {
                        extraForeignKeys++;
                    }
                }

                if (extraPrimaryKeys > 0) {
                    incrementViolationCount(violations, "PRIMARY_KEY", "IS_EXTRA", extraPrimaryKeys, expectedName);
                }
                if (extraForeignKeys > 0) {
                    incrementViolationCount(violations, "FOREIGN_KEY", "IS_EXTRA", extraForeignKeys, expectedName);
                }
                if (extraPrimaryKeys + extraForeignKeys > 0) {
                    incrementViolationCount(violations, "CONSTRAINT_LOCAL", "IS_EXTRA", extraPrimaryKeys + extraForeignKeys, expectedName);
                }
            }
        }

        if (actualTables != null) {
            for (TableMetadata actualTable : actualTables) {
                String actualTableName = normalizeCreateIdentifier(actualTable.getTableName(), caseSensitive);
                if (!expectedTableNames.contains(actualTableName)) {
                    incrementViolationCount(violations, "TABLE", "IS_EXTRA", 1, actualTable.getTableName());
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

    private static String buildCreateViolationTitle(String target, String condition) {
        String normalizedTarget = target == null ? "" : target.toUpperCase(Locale.ROOT);
        String normalizedCondition = condition == null ? "" : condition.toUpperCase(Locale.ROOT);

        if ("TABLE".equals(normalizedTarget) && "IS_MISSING".equals(normalizedCondition)) {
            return "Thiếu bảng";
        }
        if ("TABLE".equals(normalizedTarget) && "IS_EXTRA".equals(normalizedCondition)) {
            return "Dư bảng";
        }
        if ("COLUMN".equals(normalizedTarget) && "IS_MISSING".equals(normalizedCondition)) {
            return "Thiếu cột";
        }
        if ("COLUMN".equals(normalizedTarget) && "IS_EXTRA".equals(normalizedCondition)) {
            return "Dư cột";
        }
        if ("DATA_TYPE".equals(normalizedTarget) && "TYPE_MISMATCH".equals(normalizedCondition)) {
            return "Sai kiểu dữ liệu cột";
        }
        if ("COLUMN_ORDER".equals(normalizedTarget) && "OUT_OF_ORDER".equals(normalizedCondition)) {
            return "Sai thứ tự cột";
        }
        if ("PRIMARY_KEY".equals(normalizedTarget) && "IS_MISSING".equals(normalizedCondition)) {
            return "Thiếu khóa chính";
        }
        if ("PRIMARY_KEY".equals(normalizedTarget) && "IS_EXTRA".equals(normalizedCondition)) {
            return "Dư khóa chính";
        }
        if ("FOREIGN_KEY".equals(normalizedTarget) && "IS_MISSING".equals(normalizedCondition)) {
            return "Thiếu khóa ngoại";
        }
        if ("FOREIGN_KEY".equals(normalizedTarget) && "IS_EXTRA".equals(normalizedCondition)) {
            return "Dư khóa ngoại";
        }
        if ("FOREIGN_KEY".equals(normalizedTarget) && "REFERENCE_ERROR".equals(normalizedCondition)) {
            return "Sai tham chiếu khóa ngoại";
        }
        if ("CONSTRAINT_LOCAL".equals(normalizedTarget) && "IS_MISSING".equals(normalizedCondition)) {
            return "Thiếu ràng buộc";
        }
        if ("CONSTRAINT_LOCAL".equals(normalizedTarget) && "IS_EXTRA".equals(normalizedCondition)) {
            return "Dư ràng buộc";
        }

        return (target == null ? "" : target) + "/" + (condition == null ? "" : condition);
    }

    // private static void incrementViolationCount(
    //         Map<String, Integer> violations,
    //         String target,
    //         String condition,
    //         int delta) {
    //     incrementViolationCount(violations, target, condition, delta, GLOBAL_SCOPE);
    // }

    private static void incrementViolationCount(
            Map<String, Integer> violations,
            String target,
            String condition,
            int delta,
            String scope) {
        if (delta <= 0) {
            return;
        }
        String normalizedScope = (scope == null || scope.isBlank()) ? GLOBAL_SCOPE : scope.trim();
        String key = target.toUpperCase(Locale.ROOT)
                + "|" + condition.toUpperCase(Locale.ROOT)
                + "|" + normalizedScope;
        violations.merge(key, delta, Integer::sum);
    }

    private static Map<String, BigDecimal> resolveCreateRuleTableBudgets(
            JsonNode rubricTables,
            BigDecimal totalPoints) {
        Map<String, BigDecimal> budgets = new LinkedHashMap<>();
        if (rubricTables == null || !rubricTables.isArray() || rubricTables.isEmpty()) {
            return budgets;
        }

        int tableCount = 0;
        for (JsonNode rubricTable : rubricTables) {
            String tableName = rubricTable.path("expected_name").asText("").trim();
            if (tableName.isBlank()) {
                continue;
            }
            tableCount++;
            double tableBudget = rubricTable.path("missing_table_penalty").asDouble(0d);
            budgets.put(tableName, BigDecimal.valueOf(Math.max(0d, tableBudget)));
        }

        if (budgets.isEmpty() || totalPoints == null || totalPoints.compareTo(BigDecimal.ZERO) <= 0) {
            return budgets;
        }

        BigDecimal equalShare = tableCount > 0
                ? totalPoints.divide(BigDecimal.valueOf(tableCount), 6, RoundingMode.HALF_UP)
                : totalPoints;
        BigDecimal sumBudgets = BigDecimal.ZERO;
        boolean hasExplicitBudget = false;
        for (BigDecimal value : budgets.values()) {
            if (value.compareTo(BigDecimal.ZERO) > 0) {
                hasExplicitBudget = true;
                break;
            }
        }
        for (Map.Entry<String, BigDecimal> entry : budgets.entrySet()) {
            if (!hasExplicitBudget || entry.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                entry.setValue(equalShare);
            }
            sumBudgets = sumBudgets.add(entry.getValue());
        }

        if (sumBudgets.compareTo(BigDecimal.ZERO) <= 0) {
            return budgets;
        }

        for (Map.Entry<String, BigDecimal> entry : budgets.entrySet()) {
            BigDecimal normalizedBudget = entry.getValue()
                    .multiply(totalPoints)
                    .divide(sumBudgets, 6, RoundingMode.HALF_UP);
            entry.setValue(normalizedBudget);
        }

        return budgets;
    }

    private static BigDecimal applyCreateScopeCap(
            String scope,
            BigDecimal requestedDeduction,
            Map<String, BigDecimal> tableBudgets,
            Map<String, BigDecimal> tableDeductions) {
        if (requestedDeduction == null || requestedDeduction.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        if (scope == null || scope.isBlank() || GLOBAL_SCOPE.equals(scope)) {
            return requestedDeduction;
        }

        BigDecimal budget = tableBudgets.get(scope);
        if (budget == null && !tableBudgets.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal value : tableBudgets.values()) {
                sum = sum.add(value);
            }
            budget = sum.divide(BigDecimal.valueOf(tableBudgets.size()), 6, RoundingMode.HALF_UP);
        }
        if (budget == null) {
            return requestedDeduction;
        }

        BigDecimal used = tableDeductions.getOrDefault(scope, BigDecimal.ZERO);
        BigDecimal remaining = budget.subtract(used);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal applied = requestedDeduction.min(remaining);
        tableDeductions.put(scope, used.add(applied));
        return applied;
    }

    private static String buildCreateRuleScopeDetail(
            String target,
            String condition,
            String scope,
            JsonNode rubricTables,
            List<TableMetadata> actualTables,
            boolean caseSensitive) {
        if (scope == null || scope.isBlank() || GLOBAL_SCOPE.equals(scope)) {
            return null;
        }

        if ("TABLE".equalsIgnoreCase(target) && "IS_MISSING".equalsIgnoreCase(condition)) {
            return "thiếu bảng";
        }
        if ("TABLE".equalsIgnoreCase(target) && "IS_EXTRA".equalsIgnoreCase(condition)) {
            return "dư bảng so với rubric";
        }

        JsonNode rubricTable = findRubricTableByName(rubricTables, scope, caseSensitive);
        TableMetadata actualTable = findTable(actualTables, scope, caseSensitive);
        if (rubricTable == null || actualTable == null) {
            return null;
        }

        Map<String, JsonNode> expectedColumns = new LinkedHashMap<>();
        List<String> expectedColumnOrder = new ArrayList<>();
        for (JsonNode rubricColumn : rubricTable.path("columns")) {
            String columnName = rubricColumn.path("name").asText("").trim();
            if (columnName.isBlank()) {
                continue;
            }
            expectedColumns.put(normalizeCreateIdentifier(columnName, caseSensitive), rubricColumn);
            expectedColumnOrder.add(columnName);
        }

        Map<String, TableMetadata.ColumnMetadata> actualColumns = new LinkedHashMap<>();
        List<String> actualColumnOrder = new ArrayList<>();
        for (TableMetadata.ColumnMetadata actualColumn : actualTable.getColumns()) {
            String columnName = actualColumn.getColumnName();
            actualColumns.put(normalizeCreateIdentifier(columnName, caseSensitive), actualColumn);
            actualColumnOrder.add(columnName);
        }

        if ("COLUMN".equalsIgnoreCase(target) && "IS_MISSING".equalsIgnoreCase(condition)) {
            List<String> missingColumns = new ArrayList<>();
            for (JsonNode rubricColumn : rubricTable.path("columns")) {
                String columnName = rubricColumn.path("name").asText("").trim();
                if (columnName.isBlank()) {
                    continue;
                }
                if (!actualColumns.containsKey(normalizeCreateIdentifier(columnName, caseSensitive))) {
                    missingColumns.add(columnName);
                }
            }
            return missingColumns.isEmpty() ? null : "thiếu cột: " + joinWithLimit(missingColumns, 8);
        }

        if ("COLUMN".equalsIgnoreCase(target) && "IS_EXTRA".equalsIgnoreCase(condition)) {
            List<String> extraColumns = new ArrayList<>();
            for (TableMetadata.ColumnMetadata actualColumn : actualTable.getColumns()) {
                String columnName = actualColumn.getColumnName();
                if (!expectedColumns.containsKey(normalizeCreateIdentifier(columnName, caseSensitive))) {
                    extraColumns.add(columnName);
                }
            }
            return extraColumns.isEmpty() ? null : "dư cột: " + joinWithLimit(extraColumns, 8);
        }

        if ("DATA_TYPE".equalsIgnoreCase(target) && "TYPE_MISMATCH".equalsIgnoreCase(condition)) {
            List<String> mismatches = new ArrayList<>();
            for (JsonNode rubricColumn : rubricTable.path("columns")) {
                String columnName = rubricColumn.path("name").asText("").trim();
                String expectedType = rubricColumn.path("expected_type").asText("").trim();
                if (columnName.isBlank() || expectedType.isBlank()) {
                    continue;
                }
                TableMetadata.ColumnMetadata actualColumn = actualColumns.get(
                        normalizeCreateIdentifier(columnName, caseSensitive));
                if (actualColumn == null) {
                    continue;
                }
                if (!matchesSqlType(actualColumn.getRawDataType(), expectedType)) {
                    mismatches.add(columnName + "(" + expectedType + "->" + actualColumn.getRawDataType() + ")");
                }
            }
            return mismatches.isEmpty() ? null : "sai kiểu cột: " + joinWithLimit(mismatches, 6);
        }

        if ("COLUMN_ORDER".equalsIgnoreCase(target) && "OUT_OF_ORDER".equalsIgnoreCase(condition)) {
            return "thứ tự kỳ vọng: [" + String.join(", ", expectedColumnOrder)
                    + "], thực tế: [" + String.join(", ", actualColumnOrder) + "]";
        }

        return null;
    }

    private static JsonNode findRubricTableByName(JsonNode rubricTables, String tableName, boolean caseSensitive) {
        if (rubricTables == null || !rubricTables.isArray() || tableName == null || tableName.isBlank()) {
            return null;
        }
        for (JsonNode rubricTable : rubricTables) {
            String expectedName = rubricTable.path("expected_name").asText("");
            if (expectedName.isBlank()) {
                continue;
            }
            boolean matched = caseSensitive
                    ? expectedName.equals(tableName)
                    : expectedName.equalsIgnoreCase(tableName);
            if (matched) {
                return rubricTable;
            }
        }
        return null;
    }

    private static String joinWithLimit(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        int safeLimit = Math.max(1, limit);
        List<String> head = values.size() > safeLimit
                ? values.subList(0, safeLimit)
                : values;
        String text = String.join(", ", head);
        if (values.size() > safeLimit) {
            text += ", ... (+" + (values.size() - safeLimit) + ")";
        }
        return text;
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
            return getActualForeignKeys(actualTable).stream()
                    .anyMatch(foreignKey -> foreignKeyMatches(foreignKey, rubricConstraint, caseSensitive));
        }

        return true;
    }

    private static List<TableMetadata.ForeignKeyMetadata> getActualForeignKeys(TableMetadata table) {
        if (table.getForeignKeys() != null && !table.getForeignKeys().isEmpty()) {
            return table.getForeignKeys();
        }

        List<TableMetadata.ForeignKeyMetadata> legacyForeignKeys = new ArrayList<>();
        if (table.getColumns() == null) {
            return legacyForeignKeys;
        }

        for (TableMetadata.ColumnMetadata column : table.getColumns()) {
            if (!column.isForeignKey()) {
                continue;
            }
            legacyForeignKeys.add(TableMetadata.ForeignKeyMetadata.builder()
                    .columns(List.of(column.getColumnName()))
                    .referencesTable(column.getReferencesTable())
                    .referencesColumns(column.getReferencesColumn() == null
                            ? List.of()
                            : List.of(column.getReferencesColumn()))
                    .build());
        }
        return legacyForeignKeys;
    }

    private static boolean foreignKeyMatches(
            TableMetadata.ForeignKeyMetadata actualForeignKey,
            JsonNode rubricConstraint,
            boolean caseSensitive) {
        JsonNode expectedColumns = rubricConstraint.path("columns");
        if (!foreignKeyLocalColumnsMatch(actualForeignKey, expectedColumns, caseSensitive)) {
            return false;
        }

        String expectedReferencesTable = rubricConstraint.path("references_table").asText("");
        if (!matchesOptional(actualForeignKey.getReferencesTable(), expectedReferencesTable, caseSensitive)) {
            return false;
        }

        JsonNode expectedReferencesColumns = rubricConstraint.path("references_columns");
        if (!expectedReferencesColumns.isArray() || expectedReferencesColumns.isEmpty()) {
            return true;
        }

        return identifierListsMatch(
                actualForeignKey.getReferencesColumns(),
                expectedReferencesColumns,
                caseSensitive);
    }

    private static boolean foreignKeyLocalColumnsMatch(
            TableMetadata.ForeignKeyMetadata actualForeignKey,
            JsonNode expectedColumns,
            boolean caseSensitive) {
        return identifierListsMatch(actualForeignKey.getColumns(), expectedColumns, caseSensitive);
    }

    private static boolean identifierListsMatch(
            List<String> actualValues,
            JsonNode expectedValues,
            boolean caseSensitive) {
        if (actualValues == null || expectedValues == null || !expectedValues.isArray()
                || actualValues.size() != expectedValues.size()) {
            return false;
        }

        for (int i = 0; i < actualValues.size(); i++) {
            if (!matches(actualValues.get(i), expectedValues.get(i).asText(""), caseSensitive)) {
                return false;
            }
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
                "message", String.format("Bảng %s: %s điểm", tableName, earned.stripTrailingZeros().toPlainString()),
                "points", earned.doubleValue());
    }

    private static String buildCappedMessage(String baseMessage, double requestedPenalty, double appliedPenalty) {
        if (requestedPenalty > 0 && appliedPenalty < requestedPenalty) {
            return baseMessage + " (bảng đã về 0 điểm)";
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

    public record CreateTableRubricGradeResult(
            BigDecimal earnedPoints,
            BigDecimal totalDeductions,
            boolean allPassed,
            String errorMessage,
            List<Map<String, Object>> details) {
    }

    private record DeductionApplication(double appliedPenalty, BigDecimal updatedTotal) {
    }
}
