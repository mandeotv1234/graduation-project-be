package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxCatalog;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxEngine;
import graduation_project_be.application.usecases.request.WhiteboxValidateRequest;
import graduation_project_be.application.usecases.response.WhiteboxValidateResponse;
import graduation_project_be.infrastructure.services.JSqlParserSelectQueryStructureAnalyzer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for WhiteboxValidateUsecase: stateless white-box validation over the shared engine.
 */
class WhiteboxValidateUsecaseTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhiteboxValidateUsecase usecase = new WhiteboxValidateUsecase(
            new WhiteboxEngine(new JSqlParserSelectQueryStructureAnalyzer(), new WhiteboxCatalog()));

    @Test
    void execute_withForbiddenSelectStarRule_violates() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 2,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        assertTrue(response.failCount() >= 1, "Expected at least 1 failure");
        assertTrue(response.cappedDeduction() > 0, "Expected deduction > 0");
        assertFalse(response.violations().isEmpty(), "Expected violations");
    }

    @Test
    void execute_emptyRules_noViolations() throws Exception {
        String rulesJson = "[]";
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        assertEquals(0, response.failCount());
        assertEquals(0, response.passCount());
        assertEquals(0, response.warnCount());
        assertEquals(0.0, response.cappedDeduction());
        assertTrue(response.violations().isEmpty());
    }

    @Test
    void execute_nullQuestionType_defaultsToSelectQuery() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 2,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                null,
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        // Should process as SELECT_QUERY
        assertTrue(response.failCount() >= 1);
    }

    @Test
    void execute_blankQuestionType_defaultsToSelectQuery() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 2,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "  ",
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        // Should process as SELECT_QUERY
        assertTrue(response.failCount() >= 1);
    }

    @Test
    void execute_questionTypeUppercased() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 2,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "select_query",
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        // Should process as SELECT_QUERY (uppercase)
        assertTrue(response.failCount() >= 1);
    }

    @Test
    void execute_passingRule_zeroDeduction() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 2,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT col1, col2 FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        assertEquals(1, response.passCount(), "Expected 1 pass");
        assertEquals(0, response.failCount());
        assertEquals(0.0, response.cappedDeduction());
    }

    @Test
    void execute_warningOnlyRule_noDeduction() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "WARNING_ONLY",
                    "penalty_value": 2,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        assertEquals(1, response.warnCount(), "Expected 1 warning");
        assertEquals(0, response.failCount());
        assertEquals(0.0, response.cappedDeduction());
    }

    @Test
    void execute_disabledRule_skipped() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": false,
                    "severity": "DEDUCTION",
                    "penalty_value": 2,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        assertEquals(0, response.failCount());
        assertEquals(0, response.warnCount());
        assertEquals(0.0, response.cappedDeduction());
    }

    @Test
    void execute_withSettings_appliesSettings() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 5,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        String settingsJson = """
                {
                  "max_total_deduction": 2,
                  "stop_on_first_violation": false
                }
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                mapper.readTree(settingsJson),
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        // Deduction should be capped at 2.0
        assertEquals(2.0, response.cappedDeduction(), 0.01);
    }

    @Test
    void execute_multipleRules_multiplePasses() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 1,
                    "penalty_unit": "ABSOLUTE"
                  },
                  {
                    "rule_id": "FORBIDDEN_SUBQUERY",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 1,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT col1, col2 FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        // Both rules should pass (no SELECT *, no subquery)
        assertEquals(2, response.passCount());
        assertEquals(0, response.failCount());
    }

    @Test
    void execute_sqlParseOkReflectsParseResult() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 1,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT col1, col2 FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        assertTrue(response.sqlParseOk(), "Valid SQL should parse");
    }

    @Test
    void execute_sqlParseFailure_setsParseOkFalse() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 1,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "INVALID SQL SYNTAX !!!",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        assertFalse(response.sqlParseOk(), "Invalid SQL should not parse");
    }

    @Test
    void execute_nullQuestionPoints() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 1,
                    "penalty_unit": "ABSOLUTE"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                null,
                null);

        WhiteboxValidateResponse response = usecase.execute(request);

        // Should still evaluate, but with 0 points for percentage-based penalties
        assertTrue(response.failCount() >= 1);
    }

    @Test
    void execute_violationViewsPopulated() throws Exception {
        String rulesJson = """
                [
                  {
                    "rule_id": "FORBIDDEN_SELECT_STAR",
                    "enabled": true,
                    "severity": "DEDUCTION",
                    "penalty_value": 2.5,
                    "penalty_unit": "ABSOLUTE",
                    "description": "Custom description"
                  }
                ]
                """;
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT * FROM a",
                mapper.readTree(rulesJson),
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        assertFalse(response.violations().isEmpty());
        WhiteboxValidateResponse.ViolationView violation = response.violations().get(0);
        assertEquals("FORBIDDEN_SELECT_STAR", violation.ruleId());
        assertEquals("FAIL", violation.status());
        assertTrue(violation.configuredPenalty() > 0);
    }

    @Test
    void execute_nullRulesArray_emptyRules() throws Exception {
        WhiteboxValidateRequest request = new WhiteboxValidateRequest(
                "SELECT_QUERY",
                "SELECT * FROM a",
                null,
                null,
                BigDecimal.TEN);

        WhiteboxValidateResponse response = usecase.execute(request);

        assertEquals(0, response.failCount());
        assertTrue(response.violations().isEmpty());
    }
}
