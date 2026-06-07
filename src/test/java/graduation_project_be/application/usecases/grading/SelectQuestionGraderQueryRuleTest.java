package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.services.SelectQueryStructureAnalyzer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * White-box QUERY-rule logic in {@link SelectQuestionGrader}, exercised with a stub analyzer so
 * no database is required. Covers deduction, comma-join handling, fail-open polarity, FAIL_ALL,
 * and the clamp.
 */
class SelectQuestionGraderQueryRuleTest {

    private final ObjectMapper om = new ObjectMapper();
    private final GradingSupport support = new GradingSupport(null, om, null);

    private SelectQuestionGrader grader(QueryStructureFacts facts) {
        SelectQueryStructureAnalyzer analyzer = sql -> facts;
        return new SelectQuestionGrader(null, om, support, analyzer);
    }

    private JsonNode rules(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static QueryStructureFacts facts(boolean parseOk, int joinCount, int fromTables,
            boolean subSelect, boolean subFrom, boolean subWhere, boolean cte, boolean groupBy,
            boolean orderBy, boolean distinct, Set<String> aggregates) {
        return new QueryStructureFacts(parseOk, joinCount, fromTables, subSelect, subFrom, subWhere,
                cte, groupBy, orderBy, distinct, aggregates, 0, false);
    }

    @Test
    void forbidJoinViolatedDeducts() {
        var grader = grader(facts(true, 1, 2, false, false, false, false, false, false, false, Set.of()));
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"FORBID_JOIN\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":2}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertEquals(0, new BigDecimal("2.00").compareTo(result.deduction()));
        assertFalse(result.failAllTriggered());
    }

    @Test
    void requireJoinSatisfiedByCommaJoinNoDeduction() {
        // comma-join: no explicit JOIN keyword but FROM has 2 tables.
        var grader = grader(facts(true, 0, 2, false, false, false, false, false, false, false, Set.of()));
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"REQUIRE_JOIN\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":3}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.deduction()));
    }

    @Test
    void parseFailForbidSkippedRequireFlaggedForReview() {
        var grader = grader(QueryStructureFacts.parseFailed());
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"REQUIRE_GROUP_BY\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":2},"
                + "{\"target\":\"QUERY\",\"condition\":\"FORBID_JOIN\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":2}]");
        var issues = new StringBuilder();
        var result = grader.calculateQueryStructureDeduction(rules, "garbage", new BigDecimal("10"), issues);
        assertEquals(0, BigDecimal.ZERO.compareTo(result.deduction()));
        assertFalse(result.failAllTriggered());
        assertTrue(issues.toString().contains("REQUIRE_GROUP_BY"), "REQUIRE_* must be flagged for review");
        assertFalse(issues.toString().contains("FORBID_JOIN"), "FORBID_* must be silently skipped");
    }

    @Test
    void failAllTriggersFlag() {
        var grader = grader(facts(true, 0, 1, true, false, false, false, false, false, false, Set.of()));
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"FORBID_SUBQUERY_IN_SELECT\","
                + "\"action\":\"FAIL_ALL\",\"penalty_value\":0}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertTrue(result.failAllTriggered());
    }

    @Test
    void deductionClampedToMaxPoints() {
        var grader = grader(facts(true, 0, 1, false, false, false, false, false, true, false, Set.of()));
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"FORBID_ORDER_BY\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":999}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("5"), new StringBuilder());
        assertEquals(0, new BigDecimal("5.00").compareTo(result.deduction()));
    }

    @Test
    void noQueryRulesNoDeduction() {
        var grader = grader(facts(true, 0, 1, false, false, false, false, false, false, false, Set.of()));
        var rules = rules("[{\"target\":\"ROW\",\"condition\":\"IS_MISSING\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":1}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.deduction()));
        assertFalse(result.failAllTriggered());
    }

    // --- Parameterized checks: nesting depth, aggregate allow-list, literal-in-WHERE ---

    private static QueryStructureFacts factsNesting(int depth) {
        return new QueryStructureFacts(true, 0, 1, false, false, false, false, false, false, false,
                Set.of(), depth, false);
    }

    @Test
    void maxNestingDepthDeductsWhenDeeperThanThreshold() {
        var grader = grader(factsNesting(3));
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"MAX_NESTING_DEPTH\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":2,\"threshold\":2}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertEquals(0, new BigDecimal("2.00").compareTo(result.deduction()));
    }

    @Test
    void maxNestingDepthNoDeductionAtOrBelowThreshold() {
        var grader = grader(factsNesting(2));
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"MAX_NESTING_DEPTH\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":2,\"threshold\":2}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.deduction()));
    }

    @Test
    void requireAggregateArgumentSatisfiedByAnyListed() {
        var grader = grader(facts(true, 0, 1, false, false, false, false, true, false, false, Set.of("SUM")));
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"REQUIRE_AGGREGATE\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":2,\"argument\":\"SUM,AVG\"}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.deduction()));
    }

    @Test
    void requireAggregateArgumentViolatedWhenNoneListedPresent() {
        var grader = grader(facts(true, 0, 1, false, false, false, false, true, false, false, Set.of("COUNT")));
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"REQUIRE_AGGREGATE\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":2,\"argument\":\"SUM,AVG\"}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertEquals(0, new BigDecimal("2.00").compareTo(result.deduction()));
    }

    @Test
    void forbidLiteralDeductsButNeverFails() {
        var grader = grader(new QueryStructureFacts(true, 0, 1, false, false, false, false, false, false, false,
                Set.of(), 0, true));
        // Configured as FAIL_ALL, but the literal check must only ever deduct.
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"FORBID_LITERAL_IN_WHERE\","
                + "\"action\":\"FAIL_ALL\",\"penalty_value\":0}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertFalse(result.failAllTriggered(), "FORBID_LITERAL_IN_WHERE must never zero the whole question");
    }

    @Test
    void forbidLiteralDeductsWhenConfiguredWithPenalty() {
        var grader = grader(new QueryStructureFacts(true, 0, 1, false, false, false, false, false, false, false,
                Set.of(), 0, true));
        var rules = rules("[{\"target\":\"QUERY\",\"condition\":\"FORBID_LITERAL_IN_WHERE\","
                + "\"action\":\"DEDUCT_POINTS\",\"penalty_value\":1.5}]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertEquals(0, new BigDecimal("1.50").compareTo(result.deduction()));
        assertFalse(result.failAllTriggered());
    }

    @Test
    void forbidLiteralOffByDefaultWhenNoRule() {
        var grader = grader(new QueryStructureFacts(true, 0, 1, false, false, false, false, false, false, false,
                Set.of(), 0, true));
        var rules = rules("[]");
        var result = grader.calculateQueryStructureDeduction(rules, "q", new BigDecimal("10"), new StringBuilder());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.deduction()));
        assertFalse(result.failAllTriggered());
    }
}
