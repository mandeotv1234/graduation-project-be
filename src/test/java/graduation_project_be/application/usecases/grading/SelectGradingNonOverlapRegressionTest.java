package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graduation_project_be.application.usecases.grading.SelectResultDiff.SelectResultEdit;
import graduation_project_be.application.usecases.grading.SelectResultScorer.ScoringResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end-of-the-logic regression suite for the SELECT non-overlap fix (plan
 * 260626-1742, Phase 3), exercised on result-set shapes drawn from real HCMUS practical exams
 * (GK LEHOI join + CSC101 GROUP BY/HAVING; CK QLNHAXE division / anti-join / self-join).
 *
 * <p>It drives the exact code Phase 1 changed — {@link SelectResultDiff} (edit-op collection) and
 * {@link SelectResultScorer} (3-tier resolution + single budget cap) — and asserts the resulting
 * <em>score</em>, not just edit counts (which {@link SelectResultDiffNonOverlapTest} already covers).
 * {@link #gradeSingleCase} reproduces the pure grading arithmetic of
 * {@code SelectQuestionGrader.gradeSelectByRubricTestCases} (structural column deduction budgeted by
 * the whole question, row/cell deduction budgeted by the per-test-case cap, summed then clamped to
 * {@code [0, points]}); the live DB wrapper (schema lifecycle, multi-case loop, setup-failure path) is
 * validated separately by the browser/e2e run, not here.
 *
 * <p>Where the fix changes a number, the assertion states the NEW (single-count) value and the comment
 * records the OLD (double-counted) behaviour, so the before/after delta is self-documenting: a fully
 * correct answer is unchanged at full marks, a clearly-wrong answer is still penalised, and only the
 * previously over-deducted shapes (NULL cell counted twice, etc.) now deduct once.
 */
class SelectGradingNonOverlapRegressionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GradingSupport support = new GradingSupport(null, objectMapper, null);
    private final JsonNode noModifiers = objectMapper.createArrayNode();
    /** No teacher rules -> every violation resolves to SELECT DEFAULT_WEIGHTS (F5: no free pass). */
    private final JsonNode noRules = objectMapper.createArrayNode();

    // ---- fixture helpers -------------------------------------------------------------------------

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

    /** A grading_rules[] array carrying one explicit rule (target|condition -> action/penalty). */
    private ArrayNode rules(String target, String condition, String action, double penalty) {
        ArrayNode array = objectMapper.createArrayNode();
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("target", target);
        rule.put("condition", condition);
        rule.put("action", action);
        rule.put("penalty_value", penalty);
        array.add(rule);
        return array;
    }

    /**
     * Mirrors the pure deduction arithmetic of {@code gradeSelectByRubricTestCases} for one test case:
     * column edits scored against the whole-question budget + row/cell edits scored against the
     * per-case cap, summed and clamped into {@code [0, points]}. Returns the earned points.
     */
    private BigDecimal gradeSingleCase(
            BigDecimal points,
            BigDecimal caseMaxPenalty,
            List<String> expectedColumns,
            List<Map<String, Object>> expectedRows,
            List<String> actualColumns,
            List<Map<String, Object>> actualRows,
            boolean orderSensitive,
            JsonNode selectRules) {
        List<SelectResultEdit> columnEdits =
                SelectResultDiff.collectColumnEdits(expectedColumns, actualColumns);
        BigDecimal structural = columnEdits.isEmpty()
                ? BigDecimal.ZERO
                : SelectResultScorer.score(columnEdits, selectRules, points, support)
                        .totalDeduction().setScale(2, RoundingMode.HALF_UP);

        List<SelectResultEdit> rowCellEdits = SelectResultDiff.collectRowAndCellEdits(
                expectedColumns, actualColumns, expectedRows, actualRows, orderSensitive, noModifiers, support);
        BigDecimal caseDeduction = rowCellEdits.isEmpty()
                ? BigDecimal.ZERO
                : SelectResultScorer.score(rowCellEdits, selectRules, caseMaxPenalty, support)
                        .totalDeduction().setScale(2, RoundingMode.HALF_UP);

        BigDecimal total = BigDecimal.ZERO;
        if (caseDeduction.signum() > 0) {
            total = total.add(caseDeduction);
        }
        if (structural.signum() > 0) {
            total = total.add(structural);
        }
        BigDecimal earned = points.subtract(total).setScale(2, RoundingMode.HALF_UP);
        if (earned.signum() < 0) {
            earned = BigDecimal.ZERO;
        }
        if (earned.compareTo(points) > 0) {
            earned = points;
        }
        return earned;
    }

    private static void assertScale2(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual.toPlainString());
    }

    // ---- correct-full / clearly-wrong baselines (must NOT regress) -------------------------------

    // GK LEHOI ⋈ HOCVIEN: a fully correct answer earns full marks (deduction 0). This is the
    // invariant the refactor must never break.
    @Test
    void correctJoinAnswerEarnsFullMarks() {
        List<String> cols = List.of("MaLH", "TenLH", "MaHV", "HoTen");
        List<Map<String, Object>> expected = List.of(
                row(cols, "LH01", "Le hoi Ao dai", "HV01", "An"),
                row(cols, "LH01", "Le hoi Ao dai", "HV02", "Binh"),
                row(cols, "LH02", "Le hoi Hoa", "HV03", "Cuong"));

        BigDecimal earned = gradeSingleCase(
                new BigDecimal("10"), new BigDecimal("2"), cols, expected, cols, expected, false, noRules);

        assertScale2("10.00", earned);
    }

    // A student who drops the join filter returns extra (dangling) rows -> ROW IS_EXTRA, still
    // penalised. caseCap 2.00, one extra row, ROW|IS_EXTRA default 10% -> 0.20 deducted.
    @Test
    void missingJoinFilterReturnsExtraRowsAndIsPenalised() {
        List<String> cols = List.of("MaLH", "TenLH", "MaHV", "HoTen");
        List<Map<String, Object>> expected = List.of(
                row(cols, "LH01", "Le hoi Ao dai", "HV01", "An"));
        List<Map<String, Object>> studentExtra = List.of(
                row(cols, "LH01", "Le hoi Ao dai", "HV01", "An"),
                row(cols, "LH02", "Le hoi Hoa", "HV09", "Zoe")); // dangling row, filter missing

        BigDecimal earned = gradeSingleCase(
                new BigDecimal("10"), new BigDecimal("2"), cols, expected, cols, studentExtra, false, noRules);

        assertScale2("9.80", earned); // 10 - (1 extra row * 10% of 2.00)
        assertTrue(earned.compareTo(new BigDecimal("10")) < 0, "a clearly-wrong answer must lose marks");
    }

    // CSC101 GROUP BY / HAVING: one group's aggregate is wrong while the key still corresponds ->
    // a single CELL_VALUE NOT_EQUAL (partial credit), NOT a row missing + extra pair.
    @Test
    void groupByWrongAggregateIsSingleCellNotRowChurn() {
        List<String> cols = List.of("MaLH", "SoLuongHV");
        List<Map<String, Object>> expected = List.of(
                row(cols, "LH01", 2),
                row(cols, "LH02", 5));
        List<Map<String, Object>> student = List.of(
                row(cols, "LH01", 2),
                row(cols, "LH02", 4)); // wrong count for LH02

        List<SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, student, false, noModifiers, support);
        assertEquals(1, countFor(edits, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "ROW", "IS_MISSING"));
        assertEquals(0, countFor(edits, "ROW", "IS_EXTRA"));

        // caseCap 2.00, CELL_VALUE|NOT_EQUAL default 5% -> 0.10.
        BigDecimal earned = gradeSingleCase(
                new BigDecimal("10"), new BigDecimal("2"), cols, expected, cols, student, false, noRules);
        assertScale2("9.90", earned);
    }

    // ---- the fix: one root error -> one deduction (before/after delta) ---------------------------

    // HEADLINE REGRESSION (F1). A cell that is NULL where the answer is non-null is exactly one
    // IS_NULL. OLD additive behaviour counted it in BOTH wrongCells (NOT_EQUAL) AND nullViolations
    // (IS_NULL) -> two deductions; the fix collapses it to one.
    @Test
    void nullCellCountedOnceNotTwice() {
        List<String> cols = List.of("MaHV", "DiemTB");
        List<Map<String, Object>> expected = List.of(row(cols, "HV01", new BigDecimal("8.5")));
        List<Map<String, Object>> student = List.of(row(cols, "HV01", null)); // same key, NULL value

        List<SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, student, false, noModifiers, support);
        assertEquals(1, countFor(edits, "CELL_VALUE", "IS_NULL"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "NOT_EQUAL")); // OLD: also 1 here -> double count

        // NEW: one IS_NULL * 5% of caseCap 2.00 = 0.10  (OLD additive: 0.20 = IS_NULL + NOT_EQUAL).
        ScoringResult scored = SelectResultScorer.score(edits, noRules, new BigDecimal("2"), support);
        assertScale2("0.10", scored.totalDeduction().setScale(2, RoundingMode.HALF_UP));
    }

    // Regression guard (audit correction): a same-position aliased/renamed column is ONE COLUMN
    // NOT_EQUAL, never split into IS_MISSING + IS_EXTRA. CSC101 students often alias COUNT(*) AS Tong
    // vs the answer's SoLuong.
    @Test
    void renamedColumnIsSingleNotEqual() {
        List<SelectResultEdit> edits = SelectResultDiff.collectColumnEdits(
                List.of("MaLH", "SoLuong"), List.of("MaLH", "Tong"));
        assertEquals(1, countFor(edits, "COLUMN", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "COLUMN", "IS_MISSING"));
        assertEquals(0, countFor(edits, "COLUMN", "IS_EXTRA"));

        // Structural budget = whole question (10). COLUMN|NOT_EQUAL default 10% -> 1.00, charged once.
        ScoringResult scored = SelectResultScorer.score(edits, noRules, new BigDecimal("10"), support);
        assertScale2("1.00", scored.totalDeduction().setScale(2, RoundingMode.HALF_UP));
    }

    // Cascade: a wholly missing projection column (QLNHAXE: student omits TenXe) is one COLUMN
    // IS_MISSING and its cells are NOT additionally charged.
    @Test
    void missingColumnSuppressesItsCells() {
        // Student omits the trailing TenXe column (a clean missing column, no positional shift).
        List<String> expectedCols = List.of("MaXe", "Gia", "TenXe");
        List<String> actualCols = List.of("MaXe", "Gia");
        List<Map<String, Object>> expected = List.of(row(expectedCols, "X01", 800, "Vinfast"));
        List<Map<String, Object>> student = List.of(row(actualCols, "X01", 800));

        List<SelectResultEdit> edits = SelectResultDiff.collect(
                expectedCols, actualCols, expected, student, false, noModifiers, support);
        assertEquals(1, countFor(edits, "COLUMN", "IS_MISSING"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "IS_NULL"));
    }

    // Cascade + no stacked order penalty: QLNHAXE anti-join (xe chua tung duoc thue) where the student
    // returns a subset -> ROW IS_MISSING only; cells suppressed and ROW_ORDER NOT stacked on a wrong
    // row set even when grading is order-sensitive.
    @Test
    void missingRowSuppressesCellsAndDoesNotStackRowOrder() {
        List<String> cols = List.of("MaXe", "TenXe");
        List<Map<String, Object>> expected = List.of(
                row(cols, "X01", "Vinfast"),
                row(cols, "X02", "Toyota"),
                row(cols, "X03", "Honda"));
        List<Map<String, Object>> student = List.of(
                row(cols, "X01", "Vinfast"),
                row(cols, "X02", "Toyota")); // X03 missing

        List<SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, student, true, noModifiers, support);
        assertEquals(1, countFor(edits, "ROW", "IS_MISSING"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "NOT_EQUAL"));
        assertEquals(0, countFor(edits, "CELL_VALUE", "IS_NULL"));
        assertEquals(0, countFor(edits, "ROW_ORDER", "OUT_OF_ORDER"));
    }

    // Single budget cap (F6): a completely-wrong result set (e.g. a wrong join producing a disjoint
    // set of cars) accumulates many ROW IS_MISSING + IS_EXTRA edits, but the total deduction is
    // bounded by the single per-case budget. The per-row/column scope cap was deferred (plan Phase 1
    // decision); the question/case budget is the realised single cap. OLD behaviour summed unbounded.
    @Test
    void completelyWrongResultSetSaturatesAtBudgetCap() {
        List<String> cols = List.of("MaXe", "TenXe");
        List<Map<String, Object>> expected = new ArrayList<>();
        List<Map<String, Object>> student = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            expected.add(row(cols, "X" + i, "Dung" + i));
            student.add(row(cols, "Z" + i, "Sai" + i)); // disjoint set: nothing corresponds
        }

        BigDecimal cap = new BigDecimal("2");
        List<SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                cols, cols, expected, student, false, noModifiers, support);
        ScoringResult scored = SelectResultScorer.score(edits, noRules, cap, support);

        // raw = 15 IS_MISSING + 15 IS_EXTRA = 30 * (10% of 2.00) = 6.00, capped to the 2.00 budget.
        assertTrue(scored.totalDeduction().compareTo(cap) <= 0, "deduction must not exceed the budget");
        assertScale2("2.00", scored.totalDeduction().setScale(2, RoundingMode.HALF_UP));
    }

    // F5 on a HCMUS shape: a present violation with NO teacher rule still deducts the SELECT default,
    // not zero. OLD behaviour gave a free pass (matchedRuleCount == 0 -> no deduction).
    @Test
    void violationWithoutTeacherRuleStillDeductsDefault() {
        List<SelectResultEdit> edits = SelectResultDiff.collectColumnEdits(
                List.of("MaXe", "Gia", "TenXe"), List.of("MaXe", "Gia")); // trailing column missing

        ScoringResult scored = SelectResultScorer.score(edits, noRules, new BigDecimal("10"), support);
        // COLUMN|IS_MISSING default 15% of 10 = 1.50.
        assertScale2("1.50", scored.totalDeduction().setScale(2, RoundingMode.HALF_UP));
    }

    // An explicit teacher rule overrides the default: ROW_ORDER carrying SORT_ASC means "accept any
    // row order", so an order-insensitive grade ignores a reordered-but-equal set entirely.
    @Test
    void teacherRuleResolutionOverridesDefault() {
        List<String> cols = List.of("MaXe", "Gia");
        List<Map<String, Object>> expected = List.of(row(cols, "X01", 100), row(cols, "X02", 200));
        List<Map<String, Object>> student = List.of(row(cols, "X02", 200), row(cols, "X01", 100));

        // order-insensitive (SORT_ASC semantics) -> no ROW_ORDER edit, no deduction.
        BigDecimal earned = gradeSingleCase(
                new BigDecimal("10"), new BigDecimal("2"), cols, expected, cols, student, false,
                rules("ROW_ORDER", "OUT_OF_ORDER", "IGNORE", 0));
        assertScale2("10.00", earned);
    }

    // ---- before/after delta summary --------------------------------------------------------------

    // One question, three students, asserting the delta the refactor introduces:
    //  - fully correct  -> 10.00 (UNCHANGED by the fix)
    //  - clearly wrong  -> < 10  (still penalised, UNCHANGED in direction)
    //  - the over-counted NULL-cell shape -> deducts ONCE now (the only score that moves: fairer).
    @Test
    void beforeAfterDeltaOnlyOverDeductedShapeChanges() {
        List<String> cols = List.of("MaHV", "DiemTB");
        List<Map<String, Object>> answer = List.of(
                row(cols, "HV01", new BigDecimal("8.5")),
                row(cols, "HV02", new BigDecimal("6.0")));
        BigDecimal points = new BigDecimal("10");
        BigDecimal cap = new BigDecimal("2");

        BigDecimal correct = gradeSingleCase(points, cap, cols, answer, cols, answer, false, noRules);
        assertScale2("10.00", correct); // unchanged

        List<Map<String, Object>> wrongSet = List.of(
                row(cols, "HV01", new BigDecimal("8.5")),
                row(cols, "HV09", new BigDecimal("9.9"))); // wrong student row instead of HV02
        BigDecimal clearlyWrong = gradeSingleCase(points, cap, cols, answer, cols, wrongSet, false, noRules);
        assertTrue(clearlyWrong.compareTo(points) < 0, "clearly-wrong still penalised");

        List<Map<String, Object>> nullCell = List.of(
                row(cols, "HV01", new BigDecimal("8.5")),
                row(cols, "HV02", null)); // NULL where a value is expected
        BigDecimal nullCellEarned = gradeSingleCase(points, cap, cols, answer, cols, nullCell, false, noRules);
        // NEW: 10 - (1 IS_NULL * 5% of 2.00) = 9.90.  OLD additive: 10 - 0.20 (IS_NULL + NOT_EQUAL) = 9.80.
        assertScale2("9.90", nullCellEarned);
    }

    // ---- authoring adequacy lint on degenerate HCMUS datasets ------------------------------------

    // The Phase 2 linter flags degenerate trap datasets. Asserted here on HCMUS-shaped reference rows
    // so the regression suite covers authoring-time advisories alongside the grading fix.
    @Test
    void adequacyLintFlagsDegenerateDatasets() {
        SelectDatasetAdequacyLinter linter = new SelectDatasetAdequacyLinter();
        List<String> cols = List.of("MaXe", "TenXe");

        // empty reference -> HARD_WARN (cannot grade anything).
        List<SelectDatasetAdequacyLinter.Finding> empty =
                linter.lint(new ArrayList<>(), false, false);
        assertEquals(1, empty.size());
        assertEquals(SelectDatasetAdequacyLinter.CheckId.EMPTY_REF, empty.get(0).checkId());
        assertEquals(SelectDatasetAdequacyLinter.Severity.HARD_WARN, empty.get(0).severity());

        // single non-aggregate row -> TOO_FEW_ROWS (cannot exercise GROUP BY / DISTINCT / filters).
        List<Map<String, Object>> oneRow = List.of(row(cols, "X01", "Vinfast"));
        assertTrue(linter.lint(oneRow, false, false).stream()
                        .anyMatch(f -> f.checkId() == SelectDatasetAdequacyLinter.CheckId.TOO_FEW_ROWS),
                "a single-row reference should warn TOO_FEW_ROWS");

        // a selected column that is NULL in every row -> COLUMN_ALL_NULL.
        List<Map<String, Object>> allNullName = List.of(
                row(cols, "X01", null),
                row(cols, "X02", null));
        assertTrue(linter.lint(allNullName, false, false).stream()
                        .anyMatch(f -> f.checkId() == SelectDatasetAdequacyLinter.CheckId.COLUMN_ALL_NULL),
                "an all-NULL column should warn COLUMN_ALL_NULL");
    }
}
