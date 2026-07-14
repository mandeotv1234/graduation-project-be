package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Post-processes an AI-generated SELECT_QUERY rubric so that grading-rule penalties are
 * expressed relative to each test case's budget instead of as absolute points.
 *
 * <p>Why: {@code grading_rules[]} is shared by every test case of the question, but each test
 * case only owns a slice of the question's points ({@code test_cases[].penalty_value}). An
 * absolute rule penalty (e.g. 0.5đ per missing row) routinely exceeds a case budget of 0.3đ,
 * so {@link SelectResultScorer} caps it — the configured number and the applied number then
 * disagree in the grading trace. Converting {@code DEDUCT_POINTS} rules to
 * {@code DEDUCT_PERCENTAGE} of the case budget ({@code penalty / total_points * 100}) keeps one
 * rule consistent across differently-sized cases and preserves partial credit within a case.
 */
public final class SelectRubricPenaltyNormalizer {

    private SelectRubricPenaltyNormalizer() {
    }

    /** Normalizes a rubric JSON string; returns the input unchanged if it cannot be parsed. */
    public static String normalize(String rubricJson, double totalPoints, ObjectMapper objectMapper) {
        if (rubricJson == null || rubricJson.isBlank() || totalPoints <= 0d) {
            return rubricJson;
        }
        try {
            JsonNode root = objectMapper.readTree(rubricJson);
            if (normalize(root, totalPoints)) {
                return objectMapper.writeValueAsString(root);
            }
            return rubricJson;
        } catch (Exception e) {
            return rubricJson;
        }
    }

    /**
     * Normalizes in place; supports both {@code grading_payload.grading_rules} and root-level
     * {@code grading_rules} (the same lookup order as the SELECT grader). Returns true if any
     * rule was rewritten.
     */
    public static boolean normalize(JsonNode rubricRoot, double totalPoints) {
        if (rubricRoot == null || totalPoints <= 0d) {
            return false;
        }
        boolean changed = normalizeRules(rubricRoot.path("grading_payload").path("grading_rules"), totalPoints);
        changed |= normalizeRules(rubricRoot.path("grading_rules"), totalPoints);
        return changed;
    }

    private static boolean normalizeRules(JsonNode rules, double totalPoints) {
        if (rules == null || !rules.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode ruleNode : rules) {
            if (!(ruleNode instanceof ObjectNode rule)) {
                continue;
            }
            String action = rule.path("action").asText("").trim().toUpperCase();
            // Only absolute-point deductions need re-basing; IGNORE / FAIL_ALL / FAIL_ITEM /
            // DEDUCT_PERCENTAGE are already budget-independent or budget-relative.
            boolean isPointsAction = action.isBlank() || "DEDUCT_POINTS".equals(action);
            if (!isPointsAction) {
                continue;
            }
            double penalty = rule.path("penalty_value").asDouble(-1d);
            if (penalty < 0d) {
                continue;
            }
            BigDecimal percentage = BigDecimal.valueOf(penalty)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalPoints), 2, RoundingMode.HALF_UP)
                    .min(BigDecimal.valueOf(100));
            rule.put("action", "DEDUCT_PERCENTAGE");
            rule.put("penalty_value", percentage.doubleValue());
            changed = true;
        }
        return changed;
    }
}
