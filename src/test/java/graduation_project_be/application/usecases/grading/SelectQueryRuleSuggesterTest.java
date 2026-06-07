package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.infrastructure.services.JSqlParserSelectQueryStructureAnalyzer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic QUERY-rule suggestion from the model answer. Uses the real analyzer (pure, no DB)
 * so suggestions and warnings are exercised end-to-end without Gemini.
 */
class SelectQueryRuleSuggesterTest {

    private final ObjectMapper om = new ObjectMapper();
    private final SelectQueryRuleSuggester suggester =
            new SelectQueryRuleSuggester(new JSqlParserSelectQueryStructureAnalyzer(), om);

    private JsonNode arr(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasCondition(SelectQueryRuleSuggester.QueryRuleSuggestion s, String condition) {
        return s.suggestedRules().stream().anyMatch(r -> condition.equals(r.path("condition").asText()));
    }

    @Test
    void joinAnswerSuggestsRequireJoin() {
        var s = suggester.suggest("SELECT * FROM a JOIN b ON a.id = b.id", new BigDecimal("10"), null);
        assertTrue(s.parseOk());
        assertTrue(hasCondition(s, "REQUIRE_JOIN"));
    }

    @Test
    void groupByAndAggregateBothSuggested() {
        var s = suggester.suggest("SELECT dept, COUNT(*) c FROM emp GROUP BY dept", new BigDecimal("10"), null);
        assertTrue(hasCondition(s, "REQUIRE_GROUP_BY"));
        assertTrue(hasCondition(s, "REQUIRE_AGGREGATE"));
    }

    @Test
    void flatAnswerSuggestsForbidSubqueryInFrom() {
        var s = suggester.suggest("SELECT id FROM a", new BigDecimal("10"), null);
        assertTrue(hasCondition(s, "FORBID_SUBQUERY_IN_FROM"));
    }

    @Test
    void allSuggestionsAreDeductNeverFail() {
        var s = suggester.suggest("SELECT DISTINCT dept FROM emp GROUP BY dept HAVING COUNT(*) > 1",
                new BigDecimal("10"), null);
        assertFalse(s.suggestedRules().isEmpty());
        for (var r : s.suggestedRules()) {
            assertEquals("QUERY", r.path("target").asText());
            assertEquals("DEDUCT_POINTS", r.path("action").asText());
        }
    }

    @Test
    void requireRuleContradictingModelAnswerWarns() {
        JsonNode existing = arr("[{\"target\":\"QUERY\",\"condition\":\"REQUIRE_JOIN\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":2}]");
        var s = suggester.suggest("SELECT id FROM a", new BigDecimal("10"), existing);
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("REQUIRE_JOIN")),
                "REQUIRE_JOIN unsatisfiable by a no-join model answer must warn");
    }

    @Test
    void unparseableModelAnswerWarnsWithNoSuggestions() {
        var s = suggester.suggest("SELCT FRM where", new BigDecimal("10"), null);
        assertFalse(s.parseOk());
        assertTrue(s.suggestedRules().isEmpty());
        assertFalse(s.warnings().isEmpty());
    }

    @Test
    void enrichInjectsQueryRulesIntoGradingRules() {
        String rubric = "{\"grading_payload\":{\"grading_rules\":[],\"test_cases\":[]}}";
        String enriched = suggester.enrich(rubric, "SELECT * FROM a JOIN b ON a.id = b.id", new BigDecimal("10"));
        JsonNode rules = arr(enriched).path("grading_payload").path("grading_rules");
        boolean hasQueryRule = false;
        for (JsonNode r : rules) {
            if ("QUERY".equals(r.path("target").asText()) && "REQUIRE_JOIN".equals(r.path("condition").asText())) {
                hasQueryRule = true;
            }
        }
        assertTrue(hasQueryRule, "enrich must add a QUERY rule for a JOIN model answer");
    }

    @Test
    void enrichDoesNotDuplicateAlreadyConfiguredCondition() {
        String rubric = "{\"grading_payload\":{\"grading_rules\":["
                + "{\"target\":\"QUERY\",\"condition\":\"REQUIRE_JOIN\",\"action\":\"DEDUCT_POINTS\","
                + "\"penalty_value\":1}],\"test_cases\":[]}}";
        String enriched = suggester.enrich(rubric, "SELECT * FROM a JOIN b ON a.id = b.id", new BigDecimal("10"));
        JsonNode rules = arr(enriched).path("grading_payload").path("grading_rules");
        long requireJoinCount = 0;
        for (JsonNode r : rules) {
            if ("REQUIRE_JOIN".equals(r.path("condition").asText())) {
                requireJoinCount++;
            }
        }
        assertEquals(1, requireJoinCount, "already-configured REQUIRE_JOIN must not be duplicated");
    }

    @Test
    void enrichReturnsOriginalWhenJsonInvalid() {
        String bad = "not json";
        assertEquals(bad, suggester.enrich(bad, "SELECT id FROM a", new BigDecimal("10")));
    }

    @Test
    void enrichRecordsContradictionNote() {
        String rubric = "{\"grading_payload\":{\"grading_rules\":["
                + "{\"target\":\"QUERY\",\"condition\":\"REQUIRE_JOIN\",\"action\":\"DEDUCT_POINTS\","
                + "\"penalty_value\":1}],\"test_cases\":[]}}";
        String enriched = suggester.enrich(rubric, "SELECT id FROM a", new BigDecimal("10"));
        JsonNode notes = arr(enriched).path("grading_payload").path("query_structure_notes");
        assertNotNull(notes);
        assertTrue(notes.isArray() && notes.size() > 0, "contradiction must be recorded in query_structure_notes");
    }
}
