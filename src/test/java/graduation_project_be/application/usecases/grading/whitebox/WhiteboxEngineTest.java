package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.infrastructure.services.JSqlParserSelectQueryStructureAnalyzer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scoring/severity/cap/parser-fail behaviour of the shared white-box engine, independent of any DB.
 * Confirms the goal invariants: empty config = no-op, WARNING_ONLY changes nothing, parser-dependent
 * rules become UNVERIFIED on parse failure, and caps (null/absolute/percentage/both) apply correctly.
 */
class WhiteboxEngineTest {

    private static final String SELECT = "SELECT_QUERY";
    private static final BigDecimal TEN = BigDecimal.TEN;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhiteboxEngine engine =
            new WhiteboxEngine(new JSqlParserSelectQueryStructureAnalyzer(), new WhiteboxCatalog());

    private WhiteboxRule rule(String id, WhiteboxRuleType type, WhiteboxSeverity sev,
                              WhiteboxPenaltyUnit unit, double penalty, String paramsJson) {
        JsonNode params;
        try {
            params = mapper.readTree(paramsJson == null ? "{}" : paramsJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new WhiteboxRule(id, true, type, BigDecimal.valueOf(penalty), unit, sev, null, params);
    }

    private WhiteboxResult eval(String sql, List<WhiteboxRule> rules, WhiteboxSettings settings) {
        return engine.evaluate(SELECT, sql, rules, settings, TEN, false);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "got " + actual);
    }

    @Test
    void noRules_isNoOp() {
        WhiteboxResult r = eval("SELECT * FROM a", List.of(), WhiteboxSettings.defaults());
        assertTrue(r.isEmpty());
        assertAmount("0", r.cappedDeduction());
    }

    @Test
    void warningOnly_recordsButDoesNotDeduct() {
        WhiteboxResult r = eval("SELECT * FROM a",
                List.of(rule("FORBIDDEN_SELECT_STAR", WhiteboxRuleType.FORBIDDEN,
                        WhiteboxSeverity.WARNING_ONLY, WhiteboxPenaltyUnit.ABSOLUTE, 2, null)),
                WhiteboxSettings.defaults());
        assertAmount("0", r.cappedDeduction());
        assertEquals(WhiteboxStatus.WARN, r.violations().get(0).status());
    }

    @Test
    void deduction_absolute() {
        WhiteboxResult r = eval("SELECT * FROM a",
                List.of(rule("FORBIDDEN_SELECT_STAR", WhiteboxRuleType.FORBIDDEN,
                        WhiteboxSeverity.DEDUCTION, WhiteboxPenaltyUnit.ABSOLUTE, 2, null)),
                WhiteboxSettings.defaults());
        assertAmount("2", r.cappedDeduction());
        assertEquals(WhiteboxStatus.FAIL, r.violations().get(0).status());
    }

    @Test
    void deduction_percentageOfQuestion() {
        WhiteboxResult r = eval("SELECT * FROM a",
                List.of(rule("FORBIDDEN_SELECT_STAR", WhiteboxRuleType.FORBIDDEN,
                        WhiteboxSeverity.DEDUCTION, WhiteboxPenaltyUnit.PERCENTAGE_OF_QUESTION, 25, null)),
                WhiteboxSettings.defaults());
        assertAmount("2.5", r.cappedDeduction()); // 25% of 10
    }

    @Test
    void satisfiedRule_isPass_noDeduction() {
        WhiteboxResult r = eval("SELECT id FROM a",
                List.of(rule("FORBIDDEN_SELECT_STAR", WhiteboxRuleType.FORBIDDEN,
                        WhiteboxSeverity.DEDUCTION, WhiteboxPenaltyUnit.ABSOLUTE, 2, null)),
                WhiteboxSettings.defaults());
        assertAmount("0", r.cappedDeduction());
        assertEquals(WhiteboxStatus.PASS, r.violations().get(0).status());
    }

    @Test
    void cap_null_appliesNoCap() {
        WhiteboxResult r = eval("SELECT * FROM a CROSS JOIN b", twoFailingRules(4, 4),
                WhiteboxSettings.defaults());
        assertAmount("8", r.cappedDeduction());
    }

    @Test
    void cap_absolute_limitsTotal() {
        WhiteboxResult r = eval("SELECT * FROM a CROSS JOIN b", twoFailingRules(4, 4),
                new WhiteboxSettings(new BigDecimal("5"), null, false));
        assertAmount("5", r.cappedDeduction());
    }

    @Test
    void cap_percentage_limitsTotal() {
        WhiteboxResult r = eval("SELECT * FROM a CROSS JOIN b", twoFailingRules(4, 4),
                new WhiteboxSettings(null, new BigDecimal("40"), false)); // 40% of 10 = 4
        assertAmount("4", r.cappedDeduction());
    }

    @Test
    void cap_bothSet_takesLower() {
        WhiteboxResult r = eval("SELECT * FROM a CROSS JOIN b", twoFailingRules(4, 4),
                new WhiteboxSettings(new BigDecimal("3"), new BigDecimal("50"), false)); // min(3, 5) = 3
        assertAmount("3", r.cappedDeduction());
    }

    @Test
    void stopOnFirstViolation_haltsAfterFirstFail() {
        WhiteboxResult r = eval("SELECT * FROM a CROSS JOIN b", twoFailingRules(4, 4),
                new WhiteboxSettings(null, null, true));
        assertAmount("4", r.cappedDeduction());
        assertEquals(1, r.violations().size());
    }

    @Test
    void parserRequiredRule_onParseFailure_isUnverified_andDeductsZero() {
        WhiteboxResult r = eval("THIS IS NOT SQL ((",
                List.of(rule("FORBIDDEN_SUBQUERY", WhiteboxRuleType.FORBIDDEN,
                        WhiteboxSeverity.DEDUCTION, WhiteboxPenaltyUnit.ABSOLUTE, 3, null)),
                WhiteboxSettings.defaults());
        assertAmount("0", r.cappedDeduction());
        assertEquals(WhiteboxStatus.UNVERIFIED, r.violations().get(0).status());
        assertEquals(false, r.sqlParseOk());
    }

    @Test
    void textSafeRule_stillRunsOnUnparseableSql() {
        WhiteboxResult r = eval("SELECT * FRM (( broken",
                List.of(rule("FORBIDDEN_SELECT_STAR", WhiteboxRuleType.FORBIDDEN,
                        WhiteboxSeverity.DEDUCTION, WhiteboxPenaltyUnit.ABSOLUTE, 2, null)),
                WhiteboxSettings.defaults());
        assertAmount("2", r.cappedDeduction());
        assertEquals(WhiteboxStatus.FAIL, r.violations().get(0).status());
    }

    @Test
    void disabledRule_isSkipped() {
        WhiteboxRule disabled = new WhiteboxRule("FORBIDDEN_SELECT_STAR", false,
                WhiteboxRuleType.FORBIDDEN, BigDecimal.valueOf(2), WhiteboxPenaltyUnit.ABSOLUTE,
                WhiteboxSeverity.DEDUCTION, null, mapper.createObjectNode());
        WhiteboxResult r = eval("SELECT * FROM a", List.of(disabled), WhiteboxSettings.defaults());
        assertTrue(r.violations().isEmpty());
        assertAmount("0", r.cappedDeduction());
    }

    private List<WhiteboxRule> twoFailingRules(double p1, double p2) {
        return List.of(
                rule("FORBIDDEN_SELECT_STAR", WhiteboxRuleType.FORBIDDEN,
                        WhiteboxSeverity.DEDUCTION, WhiteboxPenaltyUnit.ABSOLUTE, p1, null),
                rule("FORBIDDEN_CROSS_JOIN", WhiteboxRuleType.FORBIDDEN,
                        WhiteboxSeverity.DEDUCTION, WhiteboxPenaltyUnit.ABSOLUTE, p2, null));
    }
}
