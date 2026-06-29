package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.application.usecases.grading.SelectResultDiff.SelectResultEdit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a penalty for each {@link SelectResultEdit} and sums them under a single budget cap.
 * Mirrors the 3-tier resolution of the CREATE weighted scorer:
 * <ol>
 *   <li>explicit {@code penalty_value} on the matching {@code grading_rules[]} entry,</li>
 *   <li>a matching rule with no explicit penalty falls back to the SELECT default weight,</li>
 *   <li>no matching rule at all also resolves to the SELECT {@link #DEFAULT_WEIGHTS} (no free pass, F5).</li>
 * </ol>
 * The budget (per-test-case cap, dataset points, or question points) is the single cap shared by
 * column + row + cell deductions (F6); {@code FAIL_ALL} short-circuits to the full budget.
 */
public final class SelectResultScorer {

    private static final Map<String, Decision> DEFAULT_WEIGHTS = defaultWeights();

    private SelectResultScorer() {
    }

    public record AppliedEdit(
            String target,
            String condition,
            String action,
            BigDecimal configuredPenalty,
            boolean ruleMatched,
            boolean failAllTriggered,
            boolean ignored,
            BigDecimal deduction,
            String summary) {
    }

    public record ScoringResult(BigDecimal totalDeduction, boolean failAllTriggered, List<AppliedEdit> applied) {
    }

    public static ScoringResult score(
            List<SelectResultEdit> edits,
            JsonNode gradingRules,
            BigDecimal budget,
            GradingSupport support) {
        BigDecimal safeBudget = budget == null ? BigDecimal.ZERO : budget.max(BigDecimal.ZERO);
        List<AppliedEdit> applied = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean failAll = false;

        for (SelectResultEdit edit : edits) {
            if (edit == null || edit.count() <= 0) {
                continue;
            }
            JsonNode ruleNode = gradingRules == null ? null
                    : support.findInsertRule(gradingRules, edit.target(), edit.condition());
            Decision decision = resolveDecision(edit.target(), edit.condition(), ruleNode, support);

            if (decision.isIgnore()) {
                applied.add(new AppliedEdit(edit.target(), edit.condition(), "IGNORE",
                        BigDecimal.ZERO, ruleNode != null, false, true, BigDecimal.ZERO, edit.summary()));
                continue;
            }
            if (decision.isFailAll()) {
                failAll = true;
                applied.add(new AppliedEdit(edit.target(), edit.condition(), "FAIL_ALL",
                        safeBudget, ruleNode != null, true, false, BigDecimal.ZERO, edit.summary()));
                continue;
            }

            BigDecimal perViolation = decision.perViolation(safeBudget).max(BigDecimal.ZERO);
            BigDecimal deduction = perViolation.multiply(BigDecimal.valueOf(edit.count()));
            total = total.add(deduction);
            applied.add(new AppliedEdit(edit.target(), edit.condition(), decision.action(),
                    perViolation, ruleNode != null, false, false, deduction, edit.summary()));
        }

        BigDecimal totalDeduction;
        if (failAll) {
            totalDeduction = safeBudget;
        } else {
            totalDeduction = total.min(safeBudget).max(BigDecimal.ZERO); // single budget cap (F6)
        }
        return new ScoringResult(totalDeduction.setScale(8, RoundingMode.HALF_UP), failAll, applied);
    }

    /** Human-readable Vietnamese summary of one applied edit, shared by real grading and the teacher preview. */
    public static String describe(AppliedEdit applied) {
        String label = applied.target().toUpperCase(Locale.ROOT) + "/" + applied.condition().toUpperCase(Locale.ROOT);
        if (applied.failAllTriggered()) {
            return "Rule " + label + " kích hoạt FAIL_ALL (" + applied.summary() + ").";
        }
        if (applied.ignored()) {
            return "Rule " + label + " bỏ qua vi phạm (" + applied.summary() + ").";
        }
        String formatted = applied.deduction().setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return "Rule " + label + " (" + applied.summary() + "): trừ " + formatted + " điểm.";
    }

    private static Decision resolveDecision(String target, String condition, JsonNode ruleNode, GradingSupport support) {
        Decision fallback = DEFAULT_WEIGHTS.getOrDefault(
                target + "|" + condition, Decision.percentage(BigDecimal.ZERO));
        if (ruleNode == null) {
            return fallback;
        }

        String action = ruleNode.path("action").asText("").trim().toUpperCase(Locale.ROOT);
        if (action.isBlank()) {
            action = "DEDUCT_POINTS";
        }
        double penaltyValue = support.readDoubleSetting(ruleNode.path("penalty_value"), -1d);

        switch (action) {
            case "IGNORE":
                return Decision.ignore();
            case "FAIL_ALL":
                return Decision.failAll();
            case "DEDUCT_PERCENTAGE":
                return penaltyValue >= 0d ? Decision.percentage(BigDecimal.valueOf(penaltyValue)) : fallback;
            case "DEDUCT_POINTS":
            case "FAIL_ITEM":
            default:
                return penaltyValue >= 0d ? Decision.points(BigDecimal.valueOf(penaltyValue)) : fallback;
        }
    }

    private static Map<String, Decision> defaultWeights() {
        Map<String, Decision> weights = new LinkedHashMap<>();
        weights.put("COLUMN|IS_MISSING", Decision.percentage(BigDecimal.valueOf(15)));
        weights.put("COLUMN|IS_EXTRA", Decision.percentage(BigDecimal.valueOf(15)));
        weights.put("COLUMN|NOT_EQUAL", Decision.percentage(BigDecimal.valueOf(10)));
        weights.put("COLUMN_ORDER|OUT_OF_ORDER", Decision.percentage(BigDecimal.valueOf(5)));
        weights.put("ROW|IS_MISSING", Decision.percentage(BigDecimal.valueOf(10)));
        weights.put("ROW|IS_EXTRA", Decision.percentage(BigDecimal.valueOf(10)));
        weights.put("ROW_ORDER|OUT_OF_ORDER", Decision.percentage(BigDecimal.valueOf(5)));
        weights.put("CELL_VALUE|NOT_EQUAL", Decision.percentage(BigDecimal.valueOf(5)));
        weights.put("CELL_VALUE|IS_NULL", Decision.percentage(BigDecimal.valueOf(5)));
        return weights;
    }

    private record Decision(String action, BigDecimal value, boolean isIgnore, boolean isFailAll, boolean isPercentage) {
        static Decision points(BigDecimal value) {
            return new Decision("DEDUCT_POINTS", value, false, false, false);
        }

        static Decision percentage(BigDecimal value) {
            return new Decision("DEDUCT_PERCENTAGE", value, false, false, true);
        }

        static Decision ignore() {
            return new Decision("IGNORE", BigDecimal.ZERO, true, false, false);
        }

        static Decision failAll() {
            return new Decision("FAIL_ALL", BigDecimal.ZERO, false, true, false);
        }

        BigDecimal perViolation(BigDecimal budget) {
            if (isPercentage) {
                return budget.multiply(value).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            }
            return value;
        }
    }
}
