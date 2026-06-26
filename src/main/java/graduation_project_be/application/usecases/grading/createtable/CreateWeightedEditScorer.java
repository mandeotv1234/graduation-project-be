package graduation_project_be.application.usecases.grading.createtable;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CreateWeightedEditScorer {

    private static final Map<String, RuleDecision> DEFAULT_WEIGHTS = defaultWeights();

    private CreateWeightedEditScorer() {
    }

    public static CreateScoringResult score(
            List<CreateSchemaEdit> edits,
            JsonNode rubricTables,
            JsonNode gradingRules,
            BigDecimal totalPoints,
            boolean caseSensitive) {
        BigDecimal safeTotal = totalPoints == null ? BigDecimal.ZERO : totalPoints;

        Map<String, RuleDecision> rulePolicies = new LinkedHashMap<>();
        Map<String, RuleDecision> constraintFallbackPolicies = new LinkedHashMap<>();
        if (gradingRules != null && gradingRules.isArray()) {
            for (JsonNode rule : gradingRules) {
                String target = rule.path("target").asText("").trim().toUpperCase(Locale.ROOT);
                String condition = rule.path("condition").asText("").trim().toUpperCase(Locale.ROOT);
                if (target.isBlank() || condition.isBlank()) continue;

                RuleDecision decision = parseDecision(rule, "rule");

                if ("CONSTRAINT_LOCAL".equals(target)) {
                    constraintFallbackPolicies.putIfAbsent(condition, decision);
                } else {
                    rulePolicies.putIfAbsent(target + "|" + condition, decision);
                }
            }
        }

        Map<String, RuleDecision> objectPolicies = objectPolicies(rubricTables, caseSensitive);
        Map<String, BigDecimal> tableBudgets = tableBudgets(rubricTables, safeTotal, caseSensitive);
        Map<String, BigDecimal> tableDeductions = new LinkedHashMap<>();

        BigDecimal totalDeduction = BigDecimal.ZERO;
        boolean allPassed = true;
        boolean failAll = false;
        StringBuilder errors = new StringBuilder();
        List<Map<String, Object>> details = new ArrayList<>();

        for (CreateSchemaEdit edit : edits) {
            RuleDecision decision = resolveDecision(
                    edit,
                    objectPolicies,
                    rulePolicies,
                    constraintFallbackPolicies,
                    caseSensitive);

            if (decision.ignore()) {
                details.add(Map.of(
                        "type", "info",
                        "message", edit.message() + " (bỏ qua theo cấu hình)",
                        "points", 0));
                continue;
            }

            allPassed = false;

            if (decision.failAll()) {
                failAll = true;
                append(errors, errorMessage(edit) + ": kích hoạt FAIL_ALL.");
                details.add(Map.of(
                        "type", "warning",
                        "message", edit.message() + " (kích hoạt FAIL_ALL)",
                        "points", 0));
                continue;
            }

            BigDecimal requested = BigDecimal.ZERO;
            if ("DEDUCT_POINTS".equals(decision.action())) {
                requested = decision.penaltyValue() != null ? decision.penaltyValue() : BigDecimal.ZERO;
            } else if ("DEDUCT_PERCENTAGE".equals(decision.action())) {
                BigDecimal baseAmount = getBaseAmountForEdit(edit, safeTotal, tableBudgets, caseSensitive);
                BigDecimal pct = decision.penaltyValue() != null ? decision.penaltyValue() : BigDecimal.ZERO;
                requested = baseAmount.multiply(pct).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            }

            requested = requested.max(BigDecimal.ZERO).min(safeTotal);
            if (requested.compareTo(BigDecimal.ZERO) <= 0) {
                details.add(Map.of(
                        "type", "info",
                        "message", edit.message() + " (không trừ điểm)",
                        "points", 0));
                continue;
            }

            BigDecimal applied = applyTableCap(edit, requested, tableBudgets, tableDeductions, caseSensitive);
            if (applied.compareTo(BigDecimal.ZERO) <= 0) {
                details.add(Map.of(
                        "type", "info",
                        "message", edit.message() + " (bảng đã đạt mức trừ tối đa)",
                        "points", 0));
                continue;
            }

            totalDeduction = totalDeduction.add(applied);
            String formatted = format(applied);
            append(errors, errorMessage(edit) + ": trừ " + formatted + " điểm.");
            details.add(Map.of(
                    "type", "warning",
                    "message", detailMessage(edit, decision.source()),
                    "points", applied.negate().setScale(2, RoundingMode.HALF_UP).doubleValue()));
        }

        BigDecimal earned = safeTotal.subtract(totalDeduction).max(BigDecimal.ZERO);
        if (failAll) {
            earned = BigDecimal.ZERO;
            totalDeduction = safeTotal;
            allPassed = false;
        }

        return new CreateScoringResult(
                earned.setScale(2, RoundingMode.HALF_UP),
                totalDeduction.setScale(2, RoundingMode.HALF_UP),
                allPassed,
                errors.length() == 0 ? null : errors.toString().trim(),
                List.copyOf(details));
    }

    private static BigDecimal getBaseAmountForEdit(CreateSchemaEdit edit, BigDecimal safeTotal, Map<String, BigDecimal> tableBudgets, boolean caseSensitive) {
        if ("TABLE".equals(edit.target()) && "IS_EXTRA".equals(edit.condition())) {
            return safeTotal;
        }
        String tableKey = CreateSchemaNames.normalizeIdentifier(edit.table(), caseSensitive);
        BigDecimal budget = tableBudgets.get(tableKey);
        if (budget != null) {
            return budget;
        }
        if (!tableBudgets.isEmpty()) {
            return tableBudgets.values().iterator().next();
        }
        return safeTotal;
    }

    private static RuleDecision resolveDecision(
            CreateSchemaEdit edit,
            Map<String, RuleDecision> objectPolicies,
            Map<String, RuleDecision> rulePolicies,
            Map<String, RuleDecision> constraintFallbackPolicies,
            boolean caseSensitive) {
        RuleDecision objectDecision = objectPolicies.get(edit.objectKey(caseSensitive));
        if (objectDecision != null) {
            return objectDecision;
        }

        String ruleKey = edit.target() + "|" + edit.condition();
        RuleDecision defaultDecision = DEFAULT_WEIGHTS.getOrDefault(ruleKey, RuleDecision.points(BigDecimal.ZERO, "default"));

        RuleDecision ruleDecision = rulePolicies.get(ruleKey);
        if (ruleDecision != null) {
            return ruleDecision.withFallback(defaultDecision);
        }

        if (isConstraintTarget(edit.target())) {
            RuleDecision fallbackDecision = constraintFallbackPolicies.get(edit.condition());
            if (fallbackDecision != null) {
                return fallbackDecision.withFallback(defaultDecision);
            }
        }

        return defaultDecision;
    }

    private static boolean isConstraintTarget(String target) {
        return "PRIMARY_KEY".equals(target) || "FOREIGN_KEY".equals(target) || "UNIQUE".equals(target) || "CHECK".equals(target) || "DEFAULT".equals(target) || "CONSTRAINT_LOCAL".equals(target);
    }

    private static RuleDecision parseDecision(JsonNode rule, String source) {
        String action = rule.path("action").asText("DEDUCT_POINTS").trim().toUpperCase(Locale.ROOT);
        if (action.isBlank()) {
            action = "DEDUCT_POINTS";
        }
        if ("IGNORE".equals(action)) {
            return RuleDecision.ignore(source);
        }
        if ("FAIL_ALL".equals(action)) {
            return RuleDecision.failAll(source);
        }

        BigDecimal penaltyValue = null;
        if (rule.has("penalty_value") && !rule.path("penalty_value").isNull()) {
            penaltyValue = readBigDecimal(rule.path("penalty_value"), BigDecimal.ZERO).max(BigDecimal.ZERO);
        }

        if (!"DEDUCT_PERCENTAGE".equals(action) && !"DEDUCT_POINTS".equals(action)) {
            action = "DEDUCT_POINTS";
        }

        return new RuleDecision(action, penaltyValue, false, false, source);
    }

    private static Map<String, RuleDecision> objectPolicies(JsonNode rubricTables, boolean caseSensitive) {
        Map<String, RuleDecision> policies = new LinkedHashMap<>();
        if (rubricTables == null || !rubricTables.isArray()) {
            return policies;
        }

        for (JsonNode table : rubricTables) {
            String tableName = table.path("expected_name").asText("");
            if (tableName.isBlank()) {
                continue;
            }
            if (table.has("missing_table_penalty")) {
                CreateSchemaEdit edit = new CreateSchemaEdit(
                        "TABLE", "IS_MISSING", tableName, List.of(), null, null, List.of(), null, null, "");
                policies.put(edit.objectKey(caseSensitive),
                        RuleDecision.points(readBigDecimal(table.path("missing_table_penalty"), BigDecimal.ZERO), "object"));
            }

            for (JsonNode column : table.path("columns")) {
                String columnName = column.path("name").asText("");
                if (columnName.isBlank()) {
                    continue;
                }
                if (column.has("missing_column_penalty")) {
                    policies.put(new CreateSchemaEdit("COLUMN", "IS_MISSING", tableName, List.of(columnName), null, null, List.of(), null, null, "")
                                    .objectKey(caseSensitive),
                            RuleDecision.points(readBigDecimal(column.path("missing_column_penalty"), BigDecimal.ZERO), "object"));
                }
                if (column.has("type_mismatch_penalty")) {
                    policies.put(new CreateSchemaEdit("DATA_TYPE", "FAMILY_MISMATCH", tableName, List.of(columnName), null, null, List.of(), null, null, "")
                                    .objectKey(caseSensitive),
                            RuleDecision.points(readBigDecimal(column.path("type_mismatch_penalty"), BigDecimal.ZERO), "object"));
                    policies.put(new CreateSchemaEdit("DATA_TYPE", "SIZE_MISMATCH", tableName, List.of(columnName), null, null, List.of(), null, null, "")
                                    .objectKey(caseSensitive),
                            RuleDecision.points(readBigDecimal(column.path("type_mismatch_penalty"), BigDecimal.ZERO), "object"));
                }
            }

            for (JsonNode constraint : table.path("constraints")) {
                if (!constraint.has("missing_constraint_penalty")) {
                    continue;
                }
                String constraintTarget = CreateSchemaGraphBuilder.normalizeConstraintType(constraint.path("type").asText(""));
                if (constraintTarget.isBlank()) {
                    continue;
                }
                CreateSchemaEdit edit = new CreateSchemaEdit(
                        constraintTarget, "IS_MISSING", tableName, readStringList(constraint.path("columns")),
                        constraintTarget, constraint.path("references_table").asText(null), readStringList(constraint.path("references_columns")), null, null, "");
                policies.put(edit.objectKey(caseSensitive),
                        RuleDecision.points(readBigDecimal(constraint.path("missing_constraint_penalty"), BigDecimal.ZERO), "object"));
                
                CreateSchemaEdit editMismatch = new CreateSchemaEdit(
                        constraintTarget, "MISMATCH", tableName, readStringList(constraint.path("columns")),
                        constraintTarget, constraint.path("references_table").asText(null), readStringList(constraint.path("references_columns")), null, null, "");
                policies.put(editMismatch.objectKey(caseSensitive),
                        RuleDecision.points(readBigDecimal(constraint.path("missing_constraint_penalty"), BigDecimal.ZERO), "object"));
            }
        }

        return policies;
    }

    private static Map<String, BigDecimal> tableBudgets(JsonNode rubricTables, BigDecimal totalPoints, boolean caseSensitive) {
        Map<String, BigDecimal> budgets = new LinkedHashMap<>();
        if (rubricTables == null || !rubricTables.isArray() || rubricTables.isEmpty()) {
            return budgets;
        }

        BigDecimal equalShare = totalPoints.divide(
                BigDecimal.valueOf(Math.max(1, rubricTables.size())),
                6,
                RoundingMode.HALF_UP);
        for (JsonNode table : rubricTables) {
            String tableName = table.path("expected_name").asText("");
            if (tableName.isBlank()) {
                continue;
            }
            BigDecimal budget = table.has("missing_table_penalty")
                    ? readBigDecimal(table.path("missing_table_penalty"), equalShare)
                    : equalShare;
            budgets.put(CreateSchemaNames.normalizeIdentifier(tableName, caseSensitive), budget.max(BigDecimal.ZERO));
        }
        return budgets;
    }

    private static BigDecimal applyTableCap(
            CreateSchemaEdit edit,
            BigDecimal requested,
            Map<String, BigDecimal> tableBudgets,
            Map<String, BigDecimal> tableDeductions,
            boolean caseSensitive) {
        if ("TABLE".equals(edit.target()) && "IS_EXTRA".equals(edit.condition()) || tableBudgets.isEmpty()) {
            return requested;
        }

        String tableKey = CreateSchemaNames.normalizeIdentifier(edit.table(), caseSensitive);
        BigDecimal budget = tableBudgets.get(tableKey);
        if (budget == null) {
            return requested;
        }

        BigDecimal current = tableDeductions.getOrDefault(tableKey, BigDecimal.ZERO);
        BigDecimal remaining = budget.subtract(current);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal applied = requested.min(remaining);
        tableDeductions.put(tableKey, current.add(applied));
        return applied;
    }

    private static String detailMessage(CreateSchemaEdit edit, String source) {
        return edit.message();
    }

    private static String errorMessage(CreateSchemaEdit edit) {
        return edit.message();
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static BigDecimal readBigDecimal(JsonNode node, BigDecimal defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            return new BigDecimal(node.asText("").trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static void append(StringBuilder builder, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(message);
    }

    private static String format(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static Map<String, RuleDecision> defaultWeights() {
        Map<String, RuleDecision> weights = new LinkedHashMap<>();
        weights.put("TABLE|IS_MISSING", RuleDecision.percentage(BigDecimal.valueOf(100), "default"));
        weights.put("TABLE|IS_EXTRA", RuleDecision.percentage(BigDecimal.valueOf(10), "default"));
        weights.put("TABLE|NOT_EQUAL", RuleDecision.percentage(BigDecimal.valueOf(10), "default"));
        weights.put("COLUMN|IS_MISSING", RuleDecision.percentage(BigDecimal.valueOf(15), "default"));
        weights.put("COLUMN|IS_EXTRA", RuleDecision.percentage(BigDecimal.valueOf(15), "default"));
        weights.put("COLUMN|NOT_EQUAL", RuleDecision.percentage(BigDecimal.valueOf(10), "default"));
        weights.put("DATA_TYPE|FAMILY_MISMATCH", RuleDecision.percentage(BigDecimal.valueOf(15), "default"));
        weights.put("DATA_TYPE|SIZE_MISMATCH", RuleDecision.percentage(BigDecimal.valueOf(5), "default"));
        weights.put("NULLABILITY|NOT_EQUAL", RuleDecision.percentage(BigDecimal.valueOf(5), "default"));
        weights.put("IDENTITY|NOT_EQUAL", RuleDecision.percentage(BigDecimal.valueOf(5), "default"));
        weights.put("PRIMARY_KEY|IS_MISSING", RuleDecision.percentage(BigDecimal.valueOf(20), "default"));
        weights.put("PRIMARY_KEY|IS_EXTRA", RuleDecision.percentage(BigDecimal.valueOf(10), "default"));
        weights.put("PRIMARY_KEY|MISMATCH", RuleDecision.percentage(BigDecimal.valueOf(20), "default"));
        weights.put("FOREIGN_KEY|IS_MISSING", RuleDecision.percentage(BigDecimal.valueOf(20), "default"));
        weights.put("FOREIGN_KEY|IS_EXTRA", RuleDecision.percentage(BigDecimal.valueOf(10), "default"));
        weights.put("FOREIGN_KEY|MISMATCH", RuleDecision.percentage(BigDecimal.valueOf(15), "default"));
        weights.put("UNIQUE|IS_MISSING", RuleDecision.ignore("default"));
        weights.put("UNIQUE|IS_EXTRA", RuleDecision.ignore("default"));
        weights.put("CHECK|IS_MISSING", RuleDecision.ignore("default"));
        weights.put("CHECK|IS_EXTRA", RuleDecision.ignore("default"));
        weights.put("CHECK|EXPRESSION_MISMATCH", RuleDecision.ignore("default"));
        weights.put("DEFAULT|IS_MISSING", RuleDecision.ignore("default"));
        weights.put("DEFAULT|IS_EXTRA", RuleDecision.ignore("default"));
        weights.put("DEFAULT|VALUE_MISMATCH", RuleDecision.ignore("default"));
        weights.put("CONSTRAINT_LOCAL|IS_MISSING", RuleDecision.ignore("default"));
        weights.put("CONSTRAINT_LOCAL|IS_EXTRA", RuleDecision.ignore("default"));
        return weights;
    }

    public record CreateScoringResult(
            BigDecimal earnedPoints,
            BigDecimal totalDeductions,
            boolean allPassed,
            String errorMessage,
            List<Map<String, Object>> details) {
    }

    record RuleDecision(String action, BigDecimal penaltyValue, boolean ignore, boolean failAll, String source) {
        static RuleDecision points(BigDecimal penalty, String source) {
            return new RuleDecision("DEDUCT_POINTS", penalty, false, false, source);
        }

        static RuleDecision percentage(BigDecimal percentage, String source) {
            return new RuleDecision("DEDUCT_PERCENTAGE", percentage, false, false, source);
        }

        static RuleDecision ignore(String source) {
            return new RuleDecision("IGNORE", BigDecimal.ZERO, true, false, source);
        }

        static RuleDecision failAll(String source) {
            return new RuleDecision("FAIL_ALL", BigDecimal.ZERO, false, true, source);
        }

        RuleDecision withFallback(RuleDecision fallback) {
            if (ignore || failAll || penaltyValue != null) {
                return this;
            }
            return fallback;
        }
    }
}
