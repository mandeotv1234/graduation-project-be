package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.infrastructure.services.JSqlParserSelectQueryStructureAnalyzer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TriggerWhiteboxEvaluatorsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhiteboxEngine engine =
            new WhiteboxEngine(new JSqlParserSelectQueryStructureAnalyzer(), new WhiteboxCatalog());

    @Test
    void requiredEventOnlyReadsTriggerHeaderNotBodyDml() {
        String insertTriggerWithUpdateInBody = """
                CREATE TRIGGER trg_items ON dbo.Items
                AFTER INSERT
                AS
                BEGIN
                    UPDATE dbo.Items SET UpdatedAt = SYSUTCDATETIME()
                    WHERE Id IN (SELECT Id FROM inserted);
                END
                """;

        assertEquals(BigDecimal.ONE.setScale(2), deduction(
                "TR_REQUIRED_FOR_EVENT",
                "{\"events\":[\"UPDATE\"]}",
                insertTriggerWithUpdateInBody));

        String insertUpdateTrigger = """
                CREATE TRIGGER trg_items ON dbo.Items
                AFTER INSERT, UPDATE
                AS
                BEGIN
                    SET NOCOUNT ON;
                END
                """;

        assertEquals(BigDecimal.ZERO.setScale(2), deduction(
                "TR_REQUIRED_FOR_EVENT",
                "{\"events\":[\"INSERT\",\"UPDATE\"]}",
                insertUpdateTrigger));
    }

    @Test
    void resultSetRuleAllowsExistsSubqueryButFlagsTopLevelSelect() {
        String existsTrigger = """
                CREATE TRIGGER trg_items ON dbo.Items
                AFTER INSERT
                AS
                BEGIN
                    IF EXISTS (
                        SELECT 1
                        FROM inserted
                        WHERE Amount < 0
                    )
                        THROW 50000, 'Invalid amount', 1;
                END
                """;

        assertEquals(BigDecimal.ZERO.setScale(2), deduction(
                "TR_FORBIDDEN_RESULTSET_IN_TRIGGER",
                "{}",
                existsTrigger));

        String resultSetTrigger = """
                CREATE TRIGGER trg_items ON dbo.Items
                AFTER INSERT
                AS
                BEGIN
                    SELECT *
                    FROM inserted;
                END
                """;

        assertEquals(BigDecimal.ONE.setScale(2), deduction(
                "TR_FORBIDDEN_RESULTSET_IN_TRIGGER",
                "{}",
                resultSetTrigger));
    }

    @Test
    void selectRulesCanApplyToTriggerBodies() {
        String triggerWithSelectStar = """
                CREATE TRIGGER trg_items ON dbo.Items
                AFTER INSERT
                AS
                BEGIN
                    SELECT *
                    FROM inserted;
                END
                """;

        assertEquals(BigDecimal.ONE.setScale(2), deduction(
                "FORBIDDEN_SELECT_STAR",
                "{}",
                triggerWithSelectStar));
    }

    private BigDecimal deduction(String ruleId, String paramsJson, String sql) {
        WhiteboxRule rule = new WhiteboxRule(ruleId, true, null, BigDecimal.ONE,
                WhiteboxPenaltyUnit.ABSOLUTE, WhiteboxSeverity.DEDUCTION, null, params(paramsJson));
        WhiteboxResult result = engine.evaluate("TRIGGER", sql, List.of(rule),
                WhiteboxSettings.defaults(), BigDecimal.TEN, false);
        return result.cappedDeduction();
    }

    private JsonNode params(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
