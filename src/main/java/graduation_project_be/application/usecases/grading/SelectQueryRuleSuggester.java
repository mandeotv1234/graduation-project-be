package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graduation_project_be.application.port.services.SelectQueryStructureAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Derives white-box {@code target = "QUERY"} rule suggestions from the teacher's model answer at
 * authoring time. The baseline is fully deterministic — it reads structural facts of the
 * {@code correctQuery} and proposes REQUIRE rules for constructs the answer uses and FORBID rules
 * for shortcuts the answer avoids. Suggestions are always {@code DEDUCT_POINTS} (never FAIL) so a
 * structurally-equivalent student answer cannot be zeroed out by a structural mismatch. Gemini may
 * enrich on top of this; it is not required for the baseline.
 */
public class SelectQueryRuleSuggester {

    private final SelectQueryStructureAnalyzer analyzer;
    private final ObjectMapper objectMapper;

    public SelectQueryRuleSuggester(SelectQueryStructureAnalyzer analyzer, ObjectMapper objectMapper) {
        this.analyzer = analyzer;
        this.objectMapper = objectMapper;
    }

    /** Suggested QUERY rules plus warnings for the teacher (contradictions, unparseable answer). */
    public record QueryRuleSuggestion(List<ObjectNode> suggestedRules, List<String> warnings, boolean parseOk) {
    }

    public QueryRuleSuggestion suggest(String correctQuery, BigDecimal totalPoints, JsonNode existingRules) {
        QueryStructureFacts f = analyzer.analyze(correctQuery);
        List<ObjectNode> rules = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!f.parseOk()) {
            warnings.add("Đáp án mẫu không phân tích được cấu trúc (lỗi cú pháp) — không thể gợi ý "
                    + "hoặc chấm white-box cho câu này; chỉ chấm theo kết quả.");
            return new QueryRuleSuggestion(rules, warnings, false);
        }

        BigDecimal penalty = defaultPenalty(totalPoints);
        // REQUIRE the structural constructs the model answer actually uses.
        if (f.joinCount() > 0 || f.fromTableCount() > 1) {
            rules.add(rule("Bắt buộc dùng JOIN", "REQUIRE_JOIN", penalty));
        }
        if (f.hasGroupBy()) {
            rules.add(rule("Bắt buộc dùng GROUP BY", "REQUIRE_GROUP_BY", penalty));
        }
        if (!f.aggregateFns().isEmpty()) {
            rules.add(rule("Bắt buộc dùng hàm tổng hợp", "REQUIRE_AGGREGATE", penalty));
        }
        if (f.hasCte()) {
            rules.add(rule("Bắt buộc dùng CTE (WITH)", "REQUIRE_CTE", penalty));
        }
        if (f.hasDistinct()) {
            rules.add(rule("Bắt buộc dùng DISTINCT", "REQUIRE_DISTINCT", penalty));
        }
        // FORBID the shortcut the model answer avoids (derived subquery in FROM).
        if (!f.hasSubqueryInFrom()) {
            rules.add(rule("Cấm truy vấn con trong FROM", "FORBID_SUBQUERY_IN_FROM", penalty));
        }

        // Drop any suggestion the teacher already configured, then warn on rules the model answer
        // itself would violate (the strongest signal that a rule would grade the answer unfairly).
        removeAlreadyConfigured(rules, existingRules);
        collectContradictions(existingRules, f, warnings);

        return new QueryRuleSuggestion(rules, warnings, true);
    }

    /**
     * Merges deterministic QUERY suggestions into an existing rubric JSON (under
     * {@code grading_payload.grading_rules}) and records warnings under
     * {@code grading_payload.query_structure_notes}. Returns the original JSON unchanged if it
     * cannot be parsed, so enrichment never breaks rubric generation.
     */
    public String enrich(String rubricJson, String correctQuery, BigDecimal totalPoints) {
        if (rubricJson == null || rubricJson.isBlank()) {
            return rubricJson;
        }
        try {
            JsonNode root = objectMapper.readTree(rubricJson);
            if (!root.isObject()) {
                return rubricJson;
            }
            ObjectNode rootObj = (ObjectNode) root;
            ObjectNode payload = rootObj.has("grading_payload") && rootObj.get("grading_payload").isObject()
                    ? (ObjectNode) rootObj.get("grading_payload")
                    : rootObj.putObject("grading_payload");

            ArrayNode gradingRules = payload.has("grading_rules") && payload.get("grading_rules").isArray()
                    ? (ArrayNode) payload.get("grading_rules")
                    : payload.putArray("grading_rules");

            QueryRuleSuggestion suggestion = suggest(correctQuery, totalPoints, gradingRules);
            for (ObjectNode r : suggestion.suggestedRules()) {
                gradingRules.add(r);
            }
            if (!suggestion.warnings().isEmpty()) {
                ArrayNode notes = payload.putArray("query_structure_notes");
                suggestion.warnings().forEach(notes::add);
            }
            return objectMapper.writeValueAsString(rootObj);
        } catch (Exception e) {
            return rubricJson;
        }
    }

    private void removeAlreadyConfigured(List<ObjectNode> rules, JsonNode existingRules) {
        if (existingRules == null || !existingRules.isArray()) {
            return;
        }
        rules.removeIf(r -> {
            String condition = r.path("condition").asText("");
            for (JsonNode existing : existingRules) {
                if ("QUERY".equalsIgnoreCase(existing.path("target").asText(""))
                        && condition.equalsIgnoreCase(existing.path("condition").asText(""))) {
                    return true;
                }
            }
            return false;
        });
    }

    private void collectContradictions(JsonNode existingRules, QueryStructureFacts f, List<String> warnings) {
        if (existingRules == null || !existingRules.isArray()) {
            return;
        }
        for (JsonNode existing : existingRules) {
            if (!"QUERY".equalsIgnoreCase(existing.path("target").asText(""))) {
                continue;
            }
            String condition = existing.path("condition").asText("").trim().toUpperCase(Locale.ROOT);
            if (!condition.isBlank() && modelAnswerViolates(condition, f, existing)) {
                warnings.add("Quy tắc cấu trúc \"" + condition
                        + "\" mâu thuẫn với đáp án mẫu (đáp án không thỏa quy tắc này) — "
                        + "sẽ chấm oan lời giải đúng; hãy xem lại.");
            }
        }
    }

    /**
     * Mirrors the grader's QUERY violation predicate so the suggester can flag a rule the model
     * answer would itself fail. Kept independent of the grader's private helper by design — the
     * grading engine must not be modified.
     */
    private boolean modelAnswerViolates(String condition, QueryStructureFacts f, JsonNode ruleNode) {
        return switch (condition) {
            case "REQUIRE_JOIN" -> !(f.joinCount() > 0 || f.fromTableCount() > 1);
            case "FORBID_JOIN" -> f.joinCount() > 0 || f.fromTableCount() > 1;
            case "FORBID_SUBQUERY_IN_SELECT" -> f.hasSubqueryInSelect();
            case "FORBID_SUBQUERY_IN_FROM" -> f.hasSubqueryInFrom();
            case "FORBID_SUBQUERY_IN_WHERE" -> f.hasSubqueryInWhere();
            case "REQUIRE_CTE" -> !f.hasCte();
            case "FORBID_CTE" -> f.hasCte();
            case "REQUIRE_GROUP_BY" -> !f.hasGroupBy();
            case "REQUIRE_AGGREGATE" -> f.aggregateFns().isEmpty();
            case "REQUIRE_DISTINCT" -> !f.hasDistinct();
            case "FORBID_ORDER_BY" -> f.hasOrderBy();
            case "FORBID_LITERAL_IN_WHERE" -> f.hasLiteralInWhere();
            case "MAX_NESTING_DEPTH" -> {
                int threshold = ruleNode == null ? -1 : ruleNode.path("threshold").asInt(-1);
                yield threshold >= 0 && f.maxNestingDepth() > threshold;
            }
            default -> false;
        };
    }

    private ObjectNode rule(String name, String condition, BigDecimal penalty) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("rule_name", name);
        node.put("target", "QUERY");
        node.put("condition", condition);
        node.put("action", "DEDUCT_POINTS");
        node.put("penalty_value", penalty);
        return node;
    }

    private BigDecimal defaultPenalty(BigDecimal totalPoints) {
        BigDecimal base = totalPoints == null ? BigDecimal.ZERO : totalPoints;
        BigDecimal tenth = base.multiply(BigDecimal.valueOf(0.1));
        BigDecimal floor = BigDecimal.valueOf(0.25);
        BigDecimal chosen = tenth.compareTo(floor) > 0 ? tenth : floor;
        if (chosen.compareTo(base) > 0 && base.compareTo(BigDecimal.ZERO) > 0) {
            chosen = base;
        }
        return chosen.setScale(2, RoundingMode.HALF_UP);
    }
}
