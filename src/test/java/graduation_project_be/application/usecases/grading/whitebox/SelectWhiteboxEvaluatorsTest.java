package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.infrastructure.services.JSqlParserSelectQueryStructureAnalyzer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One representative failing + passing SQL for every exposed SELECT_QUERY rule, evaluated through the
 * real engine. Each exposed catalog rule must have a backing evaluator that fails its bad example and
 * passes its good one. Penalty is DEDUCTION/ABSOLUTE 1 so a violation yields exactly 1.00.
 */
class SelectWhiteboxEvaluatorsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhiteboxCatalog catalog = new WhiteboxCatalog();
    private final WhiteboxEngine engine =
            new WhiteboxEngine(new JSqlParserSelectQueryStructureAnalyzer(), catalog);

    private record Case(String ruleId, String params, String failSql, String passSql) {
    }

    private static final List<Case> CASES = List.of(
            new Case("FORBIDDEN_SUBQUERY", "{}",
                    "SELECT id FROM a WHERE x > (SELECT MAX(y) FROM b)",
                    "SELECT id FROM a JOIN b ON a.id = b.id"),
            new Case("MAX_SUBQUERY_DEPTH", "{\"max_depth\":1}",
                    "SELECT id FROM a WHERE x IN (SELECT y FROM b WHERE z IN (SELECT w FROM c))",
                    "SELECT id FROM a WHERE x IN (SELECT y FROM b)"),
            new Case("FORBIDDEN_CORRELATED_SUBQUERY", "{}",
                    "SELECT id FROM a WHERE EXISTS (SELECT 1 FROM b WHERE b.aid = a.id)",
                    "SELECT id FROM a WHERE x IN (SELECT y FROM b)"),
            new Case("FORBIDDEN_CTE", "{}",
                    "WITH t AS (SELECT 1 AS x) SELECT x FROM t",
                    "SELECT x FROM tbl"),
            new Case("REQUIRED_CTE", "{}",
                    "SELECT x FROM tbl",
                    "WITH t AS (SELECT 1 AS x) SELECT x FROM t"),
            new Case("REQUIRED_JOIN", "{}",
                    "SELECT id FROM a",
                    "SELECT a.id FROM a JOIN b ON a.id = b.id"),
            new Case("FORBIDDEN_OLD_JOIN_SYNTAX", "{}",
                    "SELECT a.id FROM a, b WHERE a.id = b.id",
                    "SELECT a.id FROM a JOIN b ON a.id = b.id"),
            new Case("REQUIRED_LEFT_JOIN", "{}",
                    "SELECT a.id FROM a JOIN b ON a.id = b.id",
                    "SELECT a.id FROM a LEFT JOIN b ON a.id = b.id"),
            new Case("REQUIRED_INNER_JOIN", "{}",
                    "SELECT a.id FROM a JOIN b ON a.id = b.id",
                    "SELECT a.id FROM a INNER JOIN b ON a.id = b.id"),
            new Case("FORBIDDEN_CROSS_JOIN", "{}",
                    "SELECT a.id FROM a CROSS JOIN b",
                    "SELECT a.id FROM a JOIN b ON a.id = b.id"),
            new Case("MAX_JOIN_COUNT", "{\"max_joins\":1}",
                    "SELECT a.id FROM a JOIN b ON a.id = b.id JOIN c ON b.id = c.id",
                    "SELECT a.id FROM a JOIN b ON a.id = b.id"),
            new Case("FORBIDDEN_SELECT_STAR", "{}",
                    "SELECT * FROM a",
                    "SELECT id FROM a"),
            new Case("FORBIDDEN_DISTINCT", "{}",
                    "SELECT DISTINCT id FROM a",
                    "SELECT id FROM a"),
            new Case("REQUIRED_DISTINCT", "{}",
                    "SELECT id FROM a",
                    "SELECT DISTINCT id FROM a"),
            new Case("REQUIRED_GROUP_BY", "{}",
                    "SELECT id FROM a",
                    "SELECT id FROM a GROUP BY id"),
            new Case("FORBIDDEN_GROUP_BY", "{}",
                    "SELECT id FROM a GROUP BY id",
                    "SELECT id FROM a"),
            new Case("REQUIRED_HAVING", "{}",
                    "SELECT id FROM a GROUP BY id",
                    "SELECT id FROM a GROUP BY id HAVING COUNT(*) > 1"),
            new Case("FORBIDDEN_HAVING", "{}",
                    "SELECT id FROM a GROUP BY id HAVING COUNT(*) > 1",
                    "SELECT id FROM a GROUP BY id"),
            new Case("REQUIRED_AGGREGATE_FUNCTION", "{}",
                    "SELECT id FROM a",
                    "SELECT COUNT(*) FROM a"),
            new Case("FORBIDDEN_AGGREGATE_FUNCTION", "{}",
                    "SELECT COUNT(*) FROM a",
                    "SELECT id FROM a"),
            new Case("REQUIRED_ORDER_BY", "{}",
                    "SELECT id FROM a",
                    "SELECT id FROM a ORDER BY id"),
            new Case("FORBIDDEN_WINDOW_FUNCTION", "{}",
                    "SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn FROM a",
                    "SELECT id FROM a"),
            new Case("REQUIRED_WINDOW_FUNCTION", "{}",
                    "SELECT id FROM a",
                    "SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn FROM a"),
            new Case("FORBIDDEN_SET_OPERATOR", "{}",
                    "SELECT id FROM a UNION SELECT id FROM b",
                    "SELECT id FROM a"),
            new Case("REQUIRED_SET_OPERATOR", "{}",
                    "SELECT id FROM a",
                    "SELECT id FROM a UNION SELECT id FROM b"),
            new Case("FORBIDDEN_FUNCTION", "{\"functions\":[\"FORMAT\"]}",
                    "SELECT FORMAT(d, 'x') AS c FROM a",
                    "SELECT d FROM a"),
            new Case("REQUIRED_FUNCTION", "{\"functions\":[\"DATEDIFF\"]}",
                    "SELECT d FROM a",
                    "SELECT DATEDIFF(YEAR, a, b) AS c FROM a"),
            new Case("FORBIDDEN_KEYWORD", "{\"keywords\":[\"TOP\"]}",
                    "SELECT TOP 1 id FROM a",
                    "SELECT id FROM a"),
            new Case("REQUIRED_KEYWORD", "{\"keywords\":[\"BETWEEN\"]}",
                    "SELECT id FROM a",
                    "SELECT id FROM a WHERE x BETWEEN 1 AND 5"));

    @Test
    void everyExposedSelectRuleHasACaseHere() {
        assertEquals(catalog.entriesFor("SELECT_QUERY").size(), CASES.size(),
                "Add a pass/fail case for every exposed SELECT rule");
    }

    @Test
    void eachRuleFailsItsBadExampleAndPassesItsGoodOne() {
        for (Case c : CASES) {
            assertEquals(0, new BigDecimal("1").compareTo(deduction(c.ruleId(), c.params(), c.failSql())),
                    c.ruleId() + " should FAIL: " + c.failSql());
            assertEquals(0, BigDecimal.ZERO.compareTo(deduction(c.ruleId(), c.params(), c.passSql())),
                    c.ruleId() + " should PASS: " + c.passSql());
        }
    }

    @Test
    void forbiddenSubqueryAlsoCatchesHavingSubquery() {
        // HAVING subquery must trip FORBIDDEN_SUBQUERY just like WHERE/FROM/SELECT subqueries.
        BigDecimal d = deduction("FORBIDDEN_SUBQUERY", "{}",
                "SELECT dept, COUNT(*) FROM emp GROUP BY dept HAVING COUNT(*) > (SELECT AVG(c) FROM t)");
        assertEquals(0, new BigDecimal("1").compareTo(d),
                "FORBIDDEN_SUBQUERY should FAIL a HAVING subquery");
    }

    private BigDecimal deduction(String ruleId, String paramsJson, String sql) {
        WhiteboxRule rule = new WhiteboxRule(ruleId, true, null, BigDecimal.ONE,
                WhiteboxPenaltyUnit.ABSOLUTE, WhiteboxSeverity.DEDUCTION, null, params(paramsJson));
        WhiteboxResult r = engine.evaluate("SELECT_QUERY", sql, List.of(rule),
                WhiteboxSettings.defaults(), BigDecimal.TEN, false);
        return r.cappedDeduction();
    }

    private JsonNode params(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
