package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads {@code whitebox_rules[]} and {@code whitebox_settings} from rubric JSON. Tolerant by design:
 * a rubric with no white-box keys yields an empty rule list (engine no-op) and default settings, so
 * old rubrics keep their black-box behaviour unchanged.
 */
public final class WhiteboxRubricParser {

    private WhiteboxRubricParser() {
    }

    /** Rules from a {@code grading_payload} node (or any node holding {@code whitebox_rules}). */
    public static List<WhiteboxRule> rulesFromPayload(JsonNode gradingPayload) {
        return parseRules(node(gradingPayload).path("whitebox_rules"));
    }

    /** Settings from a {@code grading_payload} node (or any node holding {@code whitebox_settings}). */
    public static WhiteboxSettings settingsFromPayload(JsonNode gradingPayload) {
        return parseSettings(node(gradingPayload).path("whitebox_settings"));
    }

    /** Parses an explicit {@code whitebox_rules} array node (used by the stateless validate endpoint). */
    public static List<WhiteboxRule> parseRules(JsonNode rulesArray) {
        List<WhiteboxRule> rules = new ArrayList<>();
        if (rulesArray == null || !rulesArray.isArray()) {
            return rules;
        }
        for (JsonNode node : rulesArray) {
            String ruleId = node.path("rule_id").asText("").trim();
            if (ruleId.isEmpty()) {
                continue;
            }
            boolean enabled = node.path("enabled").asBoolean(true);
            WhiteboxRuleType type = parseEnum(node.path("type").asText(null), WhiteboxRuleType.class, null);
            BigDecimal penaltyValue = decimal(node.path("penalty_value"));
            WhiteboxPenaltyUnit unit = parseEnum(node.path("penalty_unit").asText(null),
                    WhiteboxPenaltyUnit.class, WhiteboxPenaltyUnit.ABSOLUTE);
            WhiteboxSeverity severity = parseEnum(node.path("severity").asText(null),
                    WhiteboxSeverity.class, WhiteboxSeverity.WARNING_ONLY);
            String description = node.path("description").asText(null);
            JsonNode params = node.path("params");
            rules.add(new WhiteboxRule(ruleId.toUpperCase(Locale.ROOT), enabled, type,
                    penaltyValue, unit, severity, description, params));
        }
        return rules;
    }

    /** Parses an explicit {@code whitebox_settings} object node. */
    public static WhiteboxSettings parseSettings(JsonNode settings) {
        if (settings == null || !settings.isObject()) {
            return WhiteboxSettings.defaults();
        }
        BigDecimal cap = decimal(settings.path("max_total_deduction"));
        BigDecimal pct = decimal(settings.path("max_total_deduction_pct"));
        boolean stop = settings.path("stop_on_first_violation").asBoolean(false);
        return new WhiteboxSettings(cap, pct, stop);
    }

    private static JsonNode node(JsonNode n) {
        return n == null ? MissingNode.getInstance() : n;
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        String text = n.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
