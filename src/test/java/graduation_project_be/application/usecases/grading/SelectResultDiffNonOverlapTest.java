package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.usecases.grading.SelectResultDiff.SelectResultEdit;
import graduation_project_be.application.usecases.grading.SelectResultScorer.ScoringResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-overlap behaviour of the centralized SELECT result-set comparator + scorer.
 * Each root error must be deducted ONCE: cells classify into exactly one condition,
 * a parent column/row error suppresses its child cell errors, a reorder is one
 * COLUMN_ORDER edit (not N renames), and a present violation with no teacher rule
 * still deducts the SELECT default weight.
 */
class SelectResultDiffNonOverlapTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GradingSupport support = new GradingSupport(null, objectMapper, null);
    private final JsonNode noModifiers = objectMapper.createArrayNode();
    private final JsonNode noRules = objectMapper.createArrayNode();

    private static Map<String, Object> row(List<String> columns, Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            map.put(columns.get(i), i < values.length ? values[i] : null);
        }
        return map;
    }

    private static int countFor(List<SelectResultEdit> edits, String target, String condition) {
        return edits.stream()
                .filter(e -> target.equals(e.target()) && condition.equals(e.condition()))
                .mapToInt(SelectResultEdit::count)
                .sum();
    }

    // F1: a cell where actual is NULL and expected is non-null is exactly one IS_NULL,
    // never also counted as NOT_EQUAL.
    @Test
    void nullCellIsClassifiedAsIsNullOnlyNotAlsoNotEqual() {
        List<String> cols = List.of("id", "name");
        List<Map<String, Object>> expected = List.of(row(cols, 1, "Alice"));
        List<Map<String, Object>> actual = List.of(row(cols, 1, null));

        List<SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, actual, false, noModifiers, support);

        assertEquals(1, countFor(edits, "CELL_VALUE", "IS_NULL"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "NOT_EQUAL"));
    }

    // Regression guard: a same-position renamed column is ONE COLUMN/NOT_EQUAL,
    // never split into IS_MISSING + IS_EXTRA.
    @Test
    void renamedColumnIsSingleNotEqualNotMissingPlusExtra() {
        List<SelectResultEdit> edits = SelectResultDiff.collectColumnEdits(
                List.of("id", "name"), List.of("id", "fullname"));

        assertEquals(1, countFor(edits, "COLUMN", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "COLUMN", "IS_MISSING"));
        assertEquals(0, countFor(edits, "COLUMN", "IS_EXTRA"));
    }

    // Cascade: a wholly missing column produces one COLUMN/IS_MISSING and its cells
    // are NOT counted as cell violations.
    @Test
    void missingColumnSuppressesItsCellDeductions() {
        List<String> expectedCols = List.of("id", "name", "age");
        List<String> actualCols = List.of("id", "name");
        List<Map<String, Object>> expected = List.of(row(expectedCols, 1, "Alice", 30));
        List<Map<String, Object>> actual = List.of(row(actualCols, 1, "Alice"));

        List<SelectResultEdit> edits = SelectResultDiff.collect(
                expectedCols, actualCols, expected, actual, false, noModifiers, support);

        assertEquals(1, countFor(edits, "COLUMN", "IS_MISSING"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "IS_NULL"));
    }

    // Cascade: a missing row produces one ROW/IS_MISSING and its cells are NOT counted;
    // ROW_ORDER is not stacked on top of a wrong row set.
    @Test
    void missingRowSuppressesItsCellsAndDoesNotStackRowOrder() {
        List<String> cols = List.of("id", "name");
        List<Map<String, Object>> expected = List.of(row(cols, 1, "A"), row(cols, 2, "B"));
        List<Map<String, Object>> actual = List.of(row(cols, 1, "A"));

        List<SelectResultEdit> strict = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, actual, true, noModifiers, support);

        assertEquals(1, countFor(strict, "ROW", "IS_MISSING"));
        assertEquals(0, countFor(strict, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(strict, "CELL_VALUE", "IS_NULL"));
        assertEquals(0, countFor(strict, "ROW_ORDER", "OUT_OF_ORDER"));
    }

    // F3: same column set in a different order is ONE COLUMN_ORDER edit,
    // not N per-position COLUMN/NOT_EQUAL renames.
    @Test
    void reorderedColumnsAreSingleColumnOrderNotRenames() {
        List<SelectResultEdit> edits = SelectResultDiff.collectColumnEdits(
                List.of("id", "name"), List.of("name", "id"));

        assertEquals(1, countFor(edits, "COLUMN_ORDER", "OUT_OF_ORDER"));
        assertEquals(0, countFor(edits, "COLUMN", "NOT_EQUAL"));
    }

    // F5: a present violation with no matching grading_rules[] entry deducts the
    // SELECT DEFAULT_WEIGHT, not zero. COLUMN/IS_MISSING default = 15% of budget.
    @Test
    void violationWithoutRuleDeductsDefaultWeight() {
        List<SelectResultEdit> edits = List.of(
                new SelectResultEdit("COLUMN", "IS_MISSING", 1, "thieu 1 cot"));

        ScoringResult result = SelectResultScorer.score(
                edits, noRules, new BigDecimal("10"), support);

        assertEquals(0, new BigDecimal("1.50").compareTo(result.totalDeduction()));
    }

    // F6: column + row + cell deductions for one question share ONE budget cap.
    @Test
    void deductionsShareSingleBudgetCap() {
        List<SelectResultEdit> edits = List.of(
                new SelectResultEdit("COLUMN", "IS_MISSING", 5, "thieu 5 cot"),
                new SelectResultEdit("ROW", "IS_MISSING", 5, "thieu 5 dong"),
                new SelectResultEdit("CELL_VALUE", "NOT_EQUAL", 20, "sai 20 o"));

        BigDecimal budget = new BigDecimal("10");
        ScoringResult result = SelectResultScorer.score(edits, noRules, budget, support);

        assertTrue(result.totalDeduction().compareTo(budget) <= 0,
                "total deduction must not exceed the single question budget");
        assertEquals(0, budget.compareTo(result.totalDeduction()),
                "many violations should saturate exactly at the budget cap");
    }

    // ROW_ORDER fires only when grading is order-sensitive. Callers pass order-insensitive
    // (false) when a ROW_ORDER rule carries SORT_ASC ("accept any row order"), which must
    // suppress the ROW_ORDER edit entirely.
    @Test
    void rowOrderEvaluatedOnlyWhenOrderSensitive() {
        List<String> cols = List.of("id", "name");
        List<Map<String, Object>> expected = List.of(row(cols, 1, "A"), row(cols, 2, "B"));
        List<Map<String, Object>> actual = List.of(row(cols, 2, "B"), row(cols, 1, "A")); // same set, wrong order

        List<SelectResultEdit> sensitive = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, actual, true, noModifiers, support);
        assertTrue(countFor(sensitive, "ROW_ORDER", "OUT_OF_ORDER") > 0,
                "order-sensitive grading must flag the wrong row order");
        // A pure reorder must NOT also be charged as cell mismatches or missing/extra rows.
        assertEquals(0, countFor(sensitive, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(sensitive, "CELL_VALUE", "IS_NULL"));
        assertEquals(0, countFor(sensitive, "ROW", "IS_MISSING"));
        assertEquals(0, countFor(sensitive, "ROW", "IS_EXTRA"));

        List<SelectResultEdit> insensitive = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, actual, false, noModifiers, support);
        assertEquals(0, countFor(insensitive, "ROW_ORDER", "OUT_OF_ORDER"),
                "order-insensitive grading (SORT_ASC) must not flag row order");
        assertEquals(0, countFor(insensitive, "CELL_VALUE", "NOT_EQUAL"),
                "a reordered-but-equal set has no cell errors when order is ignored");
    }

    // A fully-wrong row at the same cardinality is one missing + one extra row (so ROW and FAIL_ALL
    // rules fire), not a pile of cell mismatches.
    @Test
    void fullyWrongRowAtSameCardinalityIsMissingPlusExtraNotCells() {
        List<String> cols = List.of("id", "name");
        List<Map<String, Object>> expected = List.of(row(cols, 1, "Alice"));
        List<Map<String, Object>> actual = List.of(row(cols, 99, "Zoe"));

        List<SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, actual, false, noModifiers, support);

        assertEquals(1, countFor(edits, "ROW", "IS_MISSING"));
        assertEquals(1, countFor(edits, "ROW", "IS_EXTRA"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "IS_NULL"));
    }

    // A row that still corresponds (shares the key) but has one wrong cell stays a single CELL_VALUE
    // error, preserving partial credit instead of becoming missing + extra rows.
    @Test
    void correspondingRowWithOneWrongCellStaysCellNotRowMissingExtra() {
        List<String> cols = List.of("id", "name");
        List<Map<String, Object>> expected = List.of(row(cols, 1, "Alice"));
        List<Map<String, Object>> actual = List.of(row(cols, 1, "Alicia")); // same id, wrong name

        List<SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, actual, false, noModifiers, support);

        assertEquals(1, countFor(edits, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "ROW", "IS_MISSING"));
        assertEquals(0, countFor(edits, "ROW", "IS_EXTRA"));
    }

    // Single-column / scalar-aggregate result (e.g. SELECT COUNT(*) AS cnt): a wrong value is a CELL error,
    // not ROW missing + extra. The lone column is both identity and value, so the row always corresponds.
    @Test
    void singleColumnScalarWrongValueIsCellNotRowMissingExtra() {
        List<String> cols = List.of("cnt");
        List<Map<String, Object>> expected = List.of(row(cols, 2));
        List<Map<String, Object>> actual = List.of(row(cols, 3));

        List<SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, actual, false, noModifiers, support);

        assertEquals(1, countFor(edits, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "ROW", "IS_MISSING"));
        assertEquals(0, countFor(edits, "ROW", "IS_EXTRA"));
    }

    // F1 for a single-column scalar: expected non-null, actual NULL -> exactly one CELL_VALUE IS_NULL.
    @Test
    void singleColumnScalarNullIsIsNullNotRowMissingExtra() {
        List<String> cols = List.of("total");
        List<Map<String, Object>> expected = List.of(row(cols, 5));
        List<Map<String, Object>> actual = List.of(row(cols, (Object) null));

        List<SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, actual, false, noModifiers, support);

        assertEquals(1, countFor(edits, "CELL_VALUE", "IS_NULL"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "ROW", "IS_MISSING"));
        assertEquals(0, countFor(edits, "ROW", "IS_EXTRA"));
    }
}
