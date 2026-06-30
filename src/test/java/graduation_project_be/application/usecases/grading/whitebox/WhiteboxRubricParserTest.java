package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for WhiteboxRubricParser: parses whitebox_rules[] and whitebox_settings from JsonNode.
 */
class WhiteboxRubricParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseRules_fullRuleWithAllFields() throws Exception {
        String json = """
                [
                  {
                    "rule_id": "forbidden_select_star",
                    "enabled": true,
                    "type": "FORBIDDEN",
                    "penalty_value": "2.5",
                    "penalty_unit": "ABSOLUTE",
                    "severity": "DEDUCTION",
                    "description": "No SELECT *",
                    "params": {"max": 10}
                  }
                ]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));

        assertEquals(1, rules.size());
        WhiteboxRule rule = rules.get(0);
        assertEquals("FORBIDDEN_SELECT_STAR", rule.ruleId()); // upper-cased
        assertTrue(rule.enabled());
        assertEquals(WhiteboxRuleType.FORBIDDEN, rule.type());
        assertEquals(0, new BigDecimal("2.5").compareTo(rule.penaltyValue()));
        assertEquals(WhiteboxPenaltyUnit.ABSOLUTE, rule.penaltyUnit());
        assertEquals(WhiteboxSeverity.DEDUCTION, rule.severity());
        assertEquals("No SELECT *", rule.description());
        assertEquals(10, rule.params().path("max").asInt());
    }

    @Test
    void parseRules_customRegexRuleKeepsParams() throws Exception {
        String json = """
                [
                  {
                    "rule_id": "CUSTOM_REGEX_abc123",
                    "enabled": true,
                    "type": "CUSTOM_REGEX",
                    "penalty_value": 0.5,
                    "penalty_unit": "ABSOLUTE",
                    "severity": "DEDUCTION",
                    "description": "Rule regex tùy chỉnh",
                    "params": {
                      "name": "Cấm NOLOCK",
                      "policy": "FORBID",
                      "pattern": "\\\\bNOLOCK\\\\b",
                      "case_insensitive": true,
                      "message": "Không được dùng NOLOCK"
                    }
                  }
                ]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));

        assertEquals(1, rules.size());
        WhiteboxRule rule = rules.get(0);
        assertEquals("CUSTOM_REGEX_ABC123", rule.ruleId());
        assertEquals(WhiteboxRuleType.CUSTOM_REGEX, rule.type());
        assertEquals("Cấm NOLOCK", rule.params().path("name").asText());
        assertEquals("\\bNOLOCK\\b", rule.params().path("pattern").asText());
        assertTrue(rule.params().path("case_insensitive").asBoolean());
    }

    @Test
    void parseRules_ruleIdUpperCased() throws Exception {
        String json = """
                [{"rule_id": "forbidden_subquery", "enabled": true}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals("FORBIDDEN_SUBQUERY", rules.get(0).ruleId());
    }

    @Test
    void parseRules_enabledDefaultTrue() throws Exception {
        String json = """
                [{"rule_id": "some_rule"}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertTrue(rules.get(0).enabled());
    }

    @Test
    void parseRules_enabledFalse() throws Exception {
        String json = """
                [{"rule_id": "some_rule", "enabled": false}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertFalse(rules.get(0).enabled());
    }

    @Test
    void parseRules_severityMissingDefaultsToWarningOnly() throws Exception {
        String json = """
                [{"rule_id": "rule1"}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(WhiteboxSeverity.WARNING_ONLY, rules.get(0).severity());
    }

    @Test
    void parseRules_severityBlankDefaultsToWarningOnly() throws Exception {
        String json = """
                [{"rule_id": "rule1", "severity": ""}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(WhiteboxSeverity.WARNING_ONLY, rules.get(0).severity());
    }

    @Test
    void parseRules_severityGarbageDefaultsToWarningOnly() throws Exception {
        String json = """
                [{"rule_id": "rule1", "severity": "INVALID_SEVERITY"}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(WhiteboxSeverity.WARNING_ONLY, rules.get(0).severity());
    }

    @Test
    void parseRules_penaltyUnitMissingDefaultsToAbsolute() throws Exception {
        String json = """
                [{"rule_id": "rule1", "penalty_value": 5}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(WhiteboxPenaltyUnit.ABSOLUTE, rules.get(0).penaltyUnit());
    }

    @Test
    void parseRules_penaltyUnitBlankDefaultsToAbsolute() throws Exception {
        String json = """
                [{"rule_id": "rule1", "penalty_unit": ""}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(WhiteboxPenaltyUnit.ABSOLUTE, rules.get(0).penaltyUnit());
    }

    @Test
    void parseRules_penaltyUnitInvalidDefaultsToAbsolute() throws Exception {
        String json = """
                [{"rule_id": "rule1", "penalty_unit": "INVALID"}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(WhiteboxPenaltyUnit.ABSOLUTE, rules.get(0).penaltyUnit());
    }

    @Test
    void parseRules_typeMissingIsNull() throws Exception {
        String json = """
                [{"rule_id": "rule1"}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertNull(rules.get(0).type());
    }

    @Test
    void parseRules_typeBlankIsNull() throws Exception {
        String json = """
                [{"rule_id": "rule1", "type": ""}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertNull(rules.get(0).type());
    }

    @Test
    void parseRules_typeInvalidIsNull() throws Exception {
        String json = """
                [{"rule_id": "rule1", "type": "INVALID_TYPE"}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertNull(rules.get(0).type());
    }

    @Test
    void parseRules_skipBlankRuleId() throws Exception {
        String json = """
                [
                  {"rule_id": ""},
                  {"rule_id": "  "},
                  {"rule_id": "valid_rule"}
                ]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(1, rules.size());
        assertEquals("VALID_RULE", rules.get(0).ruleId());
    }

    @Test
    void parseRules_missingRuleIdSkipped() throws Exception {
        String json = """
                [
                  {"enabled": true},
                  {"rule_id": "rule1"}
                ]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(1, rules.size());
        assertEquals("RULE1", rules.get(0).ruleId());
    }

    @Test
    void parseRules_penaltyValueDecimal() throws Exception {
        String json = """
                [{"rule_id": "rule1", "severity": "DEDUCTION", "penalty_value": 3.75}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(0, new BigDecimal("3.75").compareTo(rules.get(0).penaltyValue()));
    }

    @Test
    void parseRules_penaltyValueString() throws Exception {
        String json = """
                [{"rule_id": "rule1", "severity": "DEDUCTION", "penalty_value": "2.5"}]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(0, new BigDecimal("2.5").compareTo(rules.get(0).penaltyValue()));
    }

    @Test
    void parseRules_warningOnlyForcesZeroPenalty() throws Exception {
        String json = """
                [{
                  "rule_id": "rule1",
                  "severity": "WARNING_ONLY",
                  "penalty_value": 25,
                  "penalty_unit": "PERCENTAGE_OF_QUESTION"
                }]
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(mapper.readTree(json));
        assertEquals(0, BigDecimal.ZERO.compareTo(rules.get(0).penaltyValue()));
        assertEquals(WhiteboxPenaltyUnit.ABSOLUTE, rules.get(0).penaltyUnit());
    }

    @Test
    void parseRules_nullRulesArray() throws Exception {
        JsonNode jsonNode = mapper.readTree("null");
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(jsonNode);
        assertTrue(rules.isEmpty());
    }

    @Test
    void parseRules_notArrayReturnsEmpty() throws Exception {
        JsonNode jsonNode = mapper.readTree("""
                {"not": "array"}
                """);
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(jsonNode);
        assertTrue(rules.isEmpty());
    }

    @Test
    void parseRules_emptyArray() throws Exception {
        JsonNode jsonNode = mapper.readTree("[]");
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(jsonNode);
        assertTrue(rules.isEmpty());
    }

    @Test
    void parseSettings_maxTotalDeductionDecimal() throws Exception {
        String json = """
                {"max_total_deduction": 5.5}
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(mapper.readTree(json));
        assertEquals(0, new BigDecimal("5.5").compareTo(settings.maxTotalDeduction()));
    }

    @Test
    void parseSettings_maxTotalDeductionString() throws Exception {
        String json = """
                {"max_total_deduction": "4.5"}
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(mapper.readTree(json));
        assertEquals(0, new BigDecimal("4.5").compareTo(settings.maxTotalDeduction()));
    }

    @Test
    void parseSettings_maxTotalDeductionNull() throws Exception {
        String json = """
                {"max_total_deduction": null}
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(mapper.readTree(json));
        assertNull(settings.maxTotalDeduction());
    }

    @Test
    void parseSettings_maxTotalDeductionBlank() throws Exception {
        String json = """
                {"max_total_deduction": ""}
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(mapper.readTree(json));
        assertNull(settings.maxTotalDeduction());
    }

    @Test
    void parseSettings_maxTotalDeductionMissing() throws Exception {
        String json = """
                {}
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(mapper.readTree(json));
        assertNull(settings.maxTotalDeduction());
    }

    @Test
    void parseSettings_maxTotalDeductionPctDecimal() throws Exception {
        String json = """
                {"max_total_deduction_pct": 25.5}
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(mapper.readTree(json));
        assertEquals(0, new BigDecimal("25.5").compareTo(settings.maxTotalDeductionPct()));
    }

    @Test
    void parseSettings_maxTotalDeductionPctNull() throws Exception {
        String json = """
                {"max_total_deduction_pct": null}
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(mapper.readTree(json));
        assertNull(settings.maxTotalDeductionPct());
    }

    @Test
    void parseSettings_stopOnFirstViolationDefaultFalse() throws Exception {
        String json = """
                {}
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(mapper.readTree(json));
        assertFalse(settings.stopOnFirstViolation());
    }

    @Test
    void parseSettings_stopOnFirstViolationTrue() throws Exception {
        String json = """
                {"stop_on_first_violation": true}
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(mapper.readTree(json));
        assertTrue(settings.stopOnFirstViolation());
    }

    @Test
    void parseSettings_nullSettingsReturnsDefaults() throws Exception {
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(null);
        assertNull(settings.maxTotalDeduction());
        assertNull(settings.maxTotalDeductionPct());
        assertFalse(settings.stopOnFirstViolation());
    }

    @Test
    void parseSettings_notObjectReturnsDefaults() throws Exception {
        JsonNode jsonNode = mapper.readTree("[]");
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(jsonNode);
        assertNull(settings.maxTotalDeduction());
        assertNull(settings.maxTotalDeductionPct());
        assertFalse(settings.stopOnFirstViolation());
    }

    @Test
    void rulesFromPayload_readsFromGradingPayload() throws Exception {
        String json = """
                {
                  "whitebox_rules": [
                    {"rule_id": "rule1", "enabled": true}
                  ]
                }
                """;
        List<WhiteboxRule> rules = WhiteboxRubricParser.rulesFromPayload(mapper.readTree(json));
        assertEquals(1, rules.size());
        assertEquals("RULE1", rules.get(0).ruleId());
    }

    @Test
    void rulesFromPayload_nullPayloadReturnsEmpty() throws Exception {
        List<WhiteboxRule> rules = WhiteboxRubricParser.rulesFromPayload(null);
        assertTrue(rules.isEmpty());
    }

    @Test
    void settingsFromPayload_readsFromGradingPayload() throws Exception {
        String json = """
                {
                  "whitebox_settings": {
                    "max_total_deduction": 8.0,
                    "stop_on_first_violation": true
                  }
                }
                """;
        WhiteboxSettings settings = WhiteboxRubricParser.settingsFromPayload(mapper.readTree(json));
        assertEquals(0, new BigDecimal("8.0").compareTo(settings.maxTotalDeduction()));
        assertTrue(settings.stopOnFirstViolation());
    }

    @Test
    void settingsFromPayload_nullPayloadReturnsDefaults() throws Exception {
        WhiteboxSettings settings = WhiteboxRubricParser.settingsFromPayload(null);
        assertNull(settings.maxTotalDeduction());
        assertFalse(settings.stopOnFirstViolation());
    }
}
