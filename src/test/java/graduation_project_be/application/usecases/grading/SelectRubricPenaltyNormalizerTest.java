package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectRubricPenaltyNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String RUBRIC = """
            {
              "total_points": 1.5,
              "grading_payload": {
                "grading_rules": [
                  {"rule_name": "Thiếu dòng", "target": "ROW", "condition": "IS_MISSING",
                   "action": "DEDUCT_POINTS", "penalty_value": 0.5},
                  {"rule_name": "Sai thứ tự dòng", "target": "ROW_ORDER", "condition": "OUT_OF_ORDER",
                   "action": "IGNORE", "penalty_value": 0},
                  {"rule_name": "Sai ô", "target": "CELL_VALUE", "condition": "NOT_EQUAL",
                   "action": "DEDUCT_PERCENTAGE", "penalty_value": 10},
                  {"rule_name": "Thiếu cột", "target": "COLUMN", "condition": "IS_MISSING",
                   "penalty_value": 3.0}
                ],
                "test_cases": [
                  {"case_id": "TC_01", "penalty_value": 0.3},
                  {"case_id": "TC_02", "penalty_value": 0.4}
                ]
              }
            }
            """;

    @Test
    void convertsDeductPointsToPercentageOfTotalPoints() throws Exception {
        String normalized = SelectRubricPenaltyNormalizer.normalize(RUBRIC, 1.5, objectMapper);
        JsonNode rules = objectMapper.readTree(normalized).path("grading_payload").path("grading_rules");

        // 0.5 / 1.5 = 33.33%
        assertEquals("DEDUCT_PERCENTAGE", rules.get(0).path("action").asText());
        assertEquals(33.33, rules.get(0).path("penalty_value").asDouble(), 0.001);

        // IGNORE and pre-existing DEDUCT_PERCENTAGE stay untouched.
        assertEquals("IGNORE", rules.get(1).path("action").asText());
        assertEquals("DEDUCT_PERCENTAGE", rules.get(2).path("action").asText());
        assertEquals(10, rules.get(2).path("penalty_value").asDouble(), 0.001);

        // Blank action defaults to DEDUCT_POINTS -> converted; 3.0/1.5 = 200% capped at 100.
        assertEquals("DEDUCT_PERCENTAGE", rules.get(3).path("action").asText());
        assertEquals(100, rules.get(3).path("penalty_value").asDouble(), 0.001);
    }

    @Test
    void leavesTestCaseBudgetsUntouched() throws Exception {
        String normalized = SelectRubricPenaltyNormalizer.normalize(RUBRIC, 1.5, objectMapper);
        JsonNode cases = objectMapper.readTree(normalized).path("grading_payload").path("test_cases");
        assertEquals(0.3, cases.get(0).path("penalty_value").asDouble(), 0.001);
        assertEquals(0.4, cases.get(1).path("penalty_value").asDouble(), 0.001);
    }

    @Test
    void returnsInputUnchangedOnInvalidJsonOrZeroPoints() {
        assertEquals("not json", SelectRubricPenaltyNormalizer.normalize("not json", 1.5, objectMapper));
        assertEquals(RUBRIC, SelectRubricPenaltyNormalizer.normalize(RUBRIC, 0d, objectMapper));
    }

    @Test
    void normalizesRootLevelGradingRules() throws Exception {
        String rubric = """
                {"grading_rules":[{"target":"ROW","condition":"IS_MISSING",
                 "action":"DEDUCT_POINTS","penalty_value":1.0}]}
                """;
        JsonNode root = objectMapper.readTree(rubric);
        assertTrue(SelectRubricPenaltyNormalizer.normalize(root, 2.0));
        JsonNode rule = root.path("grading_rules").get(0);
        assertEquals("DEDUCT_PERCENTAGE", rule.path("action").asText());
        assertEquals(50, rule.path("penalty_value").asDouble(), 0.001);
        assertFalse(rule.path("penalty_value").isMissingNode());
    }
}
