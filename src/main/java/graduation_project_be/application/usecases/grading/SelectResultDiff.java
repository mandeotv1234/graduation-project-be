package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Centralized SELECT result-set comparator. Treats a result set as columns -> rows -> cells
 * and emits a de-duplicated, mutually-exclusive, cascading list of {@link SelectResultEdit}s
 * so that one root error is reported once. Mirrors the edit-op discipline of the CREATE
 * structural grader (one atomic edit per node, parent suppresses child) without the schema-tree
 * model -- a SELECT result is a flat bag, not a hierarchy.
 *
 * <p>Non-overlap guarantees:
 * <ul>
 *   <li>A column slot is exactly one of {NOT_EQUAL (rename), IS_MISSING, IS_EXTRA}; a pure reorder
 *       of the same column set is a single COLUMN_ORDER edit, never per-position renames.</li>
 *   <li>A cell is exactly one of {IS_NULL (actual null, expected non-null), NOT_EQUAL}.</li>
 *   <li>Cells are compared only for columns that match by name at the same position, so a
 *       missing / extra / renamed column suppresses its cell deductions (cascade).</li>
 *   <li>Missing / extra rows are never paired, so their cells are not counted; ROW_ORDER is
 *       evaluated only when the row set already matches.</li>
 * </ul>
 */
public final class SelectResultDiff {

    private static final String EXACT = "EXACT";

    private SelectResultDiff() {
    }

    /** One aggregated, mutually-exclusive violation: {@code count} instances of {@code target|condition}. */
    public record SelectResultEdit(String target, String condition, int count, String summary) {
    }

    /** Column-level edits (projection): rename / missing / extra (mutually exclusive) or a single reorder. */
    public static List<SelectResultEdit> collectColumnEdits(List<String> expectedColumns, List<String> actualColumns) {
        List<SelectResultEdit> edits = new ArrayList<>();
        List<String> expected = nonBlank(expectedColumns);
        List<String> actual = nonBlank(actualColumns);
        if (expected.isEmpty() || actual.isEmpty()) {
            return edits;
        }

        // Pure reorder: same column set, same size, different sequence -> one COLUMN_ORDER (F3),
        // never N per-position renames.
        if (expected.size() == actual.size()
                && sameColumnSetIgnoreCase(expected, actual)
                && !sameColumnOrderIgnoreCase(expected, actual)) {
            edits.add(new SelectResultEdit("COLUMN_ORDER", "OUT_OF_ORDER", 1, "sai thứ tự cột"));
            return edits;
        }

        int minCols = Math.min(expected.size(), actual.size());
        int nameMismatchAtSamePosition = 0;
        for (int i = 0; i < minCols; i++) {
            if (!expected.get(i).equalsIgnoreCase(actual.get(i))) {
                nameMismatchAtSamePosition++;
            }
        }
        int missing = Math.max(0, expected.size() - actual.size());
        int extra = Math.max(0, actual.size() - expected.size());

        if (nameMismatchAtSamePosition > 0) {
            edits.add(new SelectResultEdit("COLUMN", "NOT_EQUAL", nameMismatchAtSamePosition,
                    "sai tên " + nameMismatchAtSamePosition + " cột"));
        }
        if (missing > 0) {
            edits.add(new SelectResultEdit("COLUMN", "IS_MISSING", missing, "thiếu " + missing + " cột"));
        }
        if (extra > 0) {
            edits.add(new SelectResultEdit("COLUMN", "IS_EXTRA", extra, "dư " + extra + " cột"));
        }
        return edits;
    }

    /** Row + cell edits over the columns that match by name at the same position (cascade-aware). */
    public static List<SelectResultEdit> collectRowAndCellEdits(
            List<String> expectedColumns,
            List<String> actualColumns,
            List<Map<String, Object>> expectedRows,
            List<Map<String, Object>> actualRows,
            boolean strictOrdering,
            JsonNode cellModifiers,
            GradingSupport support) {
        List<SelectResultEdit> edits = new ArrayList<>();
        List<Map<String, Object>> expected = expectedRows == null ? List.of() : expectedRows;
        List<Map<String, Object>> actual = actualRows == null ? List.of() : actualRows;

        // Only columns that match by name at the same position get their cells compared.
        // A missing / extra / renamed column is already reported at the column level (cascade).
        List<String> matchedColumns = matchedColumnsByPosition(nonBlank(expectedColumns), nonBlank(actualColumns));
        if (matchedColumns.isEmpty()) {
            // No comparable columns -> can only judge cardinality.
            int cardMissing = Math.max(0, expected.size() - actual.size());
            int cardExtra = Math.max(0, actual.size() - expected.size());
            if (cardMissing > 0) {
                edits.add(new SelectResultEdit("ROW", "IS_MISSING", cardMissing, "thiếu " + cardMissing + " dòng"));
            }
            if (cardExtra > 0) {
                edits.add(new SelectResultEdit("ROW", "IS_EXTRA", cardExtra, "dư " + cardExtra + " dòng"));
            }
            return edits;
        }

        // Multiset diff over the matched columns so one root error is reported once:
        //  (1) remove exact-signature matches  -> correct rows (no deduction);
        //  (2) pair the remaining rows that still correspond (share >=1 matched value) -> CELL errors;
        //  (3) rows left with no correspondence -> ROW IS_MISSING / IS_EXTRA;
        //  (4) ROW_ORDER only when the whole multiset matches but the positional order differs.
        List<Map<String, Object>> remExpected = new ArrayList<>(expected);
        List<Map<String, Object>> remActual = new ArrayList<>(actual);
        boolean fullRowSetMatch = removeExactRowMatches(remExpected, remActual, matchedColumns, cellModifiers, support);

        int cellIsNull = 0;
        int cellNotEqual = 0;
        int matchedRowPairs = 0;
        for (Map<String, Object> expectedRow : remExpected) {
            int idx = bestCorrespondingIndex(
                    remActual, expectedRow, matchedColumns, cellModifiers, support, matchedColumns.size() >= 2);
            if (idx < 0) {
                continue; // no corresponding actual row -> this expected row is MISSING
            }
            Map<String, Object> actualRow = remActual.remove(idx);
            matchedRowPairs++;
            for (String column : matchedColumns) {
                Object actualValue = support.getRowValueIgnoreCase(actualRow, column);
                Object expectedValue = support.getRowValueIgnoreCase(expectedRow, column);

                boolean actualNull = support.isNullLike(
                        support.applyInsertModifiers(support.normalizeValueStr(actualValue, false, false), cellModifiers));
                boolean expectedNull = support.isNullLike(
                        support.applyInsertModifiers(support.normalizeValueStr(expectedValue, false, false), cellModifiers));

                if (actualNull && expectedNull) {
                    continue; // both empty -> match
                }
                if (actualNull) {
                    cellIsNull++; // actual null where a value was expected -> IS_NULL only (mutual exclusion)
                    continue;
                }
                if (!support.valuesEqualByMatchTypeWithModifiers(actualValue, expectedValue, EXACT, cellModifiers, false, false)) {
                    cellNotEqual++;
                }
            }
        }

        int missingRows = remExpected.size() - matchedRowPairs; // expected rows with no correspondence
        int extraRows = remActual.size();                       // actual rows with no correspondence
        if (missingRows > 0) {
            edits.add(new SelectResultEdit("ROW", "IS_MISSING", missingRows, "thiếu " + missingRows + " dòng"));
        }
        if (extraRows > 0) {
            edits.add(new SelectResultEdit("ROW", "IS_EXTRA", extraRows, "dư " + extraRows + " dòng"));
        }
        if (cellIsNull > 0) {
            edits.add(new SelectResultEdit("CELL_VALUE", "IS_NULL", cellIsNull, "null " + cellIsNull + " ô dữ liệu"));
        }
        if (cellNotEqual > 0) {
            edits.add(new SelectResultEdit("CELL_VALUE", "NOT_EQUAL", cellNotEqual, "sai " + cellNotEqual + " ô dữ liệu"));
        }

        // ROW_ORDER only when the whole row multiset matches (every row correct, same cardinality)
        // but the order differs -> never stacked on cell / missing / extra errors.
        if (strictOrdering && fullRowSetMatch && expected.size() == actual.size()) {
            int rowOrder = countRowOrderViolations(actual, expected, matchedColumns, cellModifiers, support);
            if (rowOrder > 0) {
                edits.add(new SelectResultEdit("ROW_ORDER", "OUT_OF_ORDER", rowOrder, "sai thứ tự " + rowOrder + " dòng"));
            }
        }
        return edits;
    }

    /** Full diff (column + row + cell), used by the single-pass dataset paths. */
    public static List<SelectResultEdit> collect(
            List<String> expectedColumns,
            List<String> actualColumns,
            List<Map<String, Object>> expectedRows,
            List<Map<String, Object>> actualRows,
            boolean strictOrdering,
            JsonNode cellModifiers,
            GradingSupport support) {
        List<SelectResultEdit> edits = new ArrayList<>(collectColumnEdits(expectedColumns, actualColumns));
        edits.addAll(collectRowAndCellEdits(
                expectedColumns, actualColumns, expectedRows, actualRows, strictOrdering, cellModifiers, support));
        return edits;
    }

    // --- internal helpers (single source of truth, ported from the legacy per-path copies) ---

    private static List<String> nonBlank(List<String> columns) {
        List<String> result = new ArrayList<>();
        if (columns == null) {
            return result;
        }
        for (String column : columns) {
            if (column != null && !column.isBlank()) {
                result.add(column);
            }
        }
        return result;
    }

    private static List<String> matchedColumnsByPosition(List<String> expected, List<String> actual) {
        List<String> matched = new ArrayList<>();
        int minCols = Math.min(expected.size(), actual.size());
        for (int i = 0; i < minCols; i++) {
            if (expected.get(i).equalsIgnoreCase(actual.get(i))) {
                matched.add(expected.get(i));
            }
        }
        return matched;
    }

    private static boolean sameColumnSetIgnoreCase(List<String> expected, List<String> actual) {
        Set<String> expectedSet = new LinkedHashSet<>();
        for (String column : expected) {
            expectedSet.add(column.toLowerCase(Locale.ROOT));
        }
        Set<String> actualSet = new LinkedHashSet<>();
        for (String column : actual) {
            actualSet.add(column.toLowerCase(Locale.ROOT));
        }
        return expectedSet.equals(actualSet);
    }

    private static boolean sameColumnOrderIgnoreCase(List<String> expected, List<String> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equalsIgnoreCase(actual.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes exact-signature matches (multiset intersection over the matched columns) from both lists.
     * Returns true if both lists become empty (the row multisets are identical).
     */
    private static boolean removeExactRowMatches(
            List<Map<String, Object>> expectedRows,
            List<Map<String, Object>> actualRows,
            List<String> columns,
            JsonNode cellModifiers,
            GradingSupport support) {
        List<String> expectedSignatures = new ArrayList<>();
        for (Map<String, Object> row : expectedRows) {
            expectedSignatures.add(rowSignature(row, columns, cellModifiers, support));
        }
        for (int i = 0; i < actualRows.size();) {
            String sig = rowSignature(actualRows.get(i), columns, cellModifiers, support);
            int j = expectedSignatures.indexOf(sig);
            if (j >= 0) {
                expectedSignatures.remove(j);
                expectedRows.remove(j);
                actualRows.remove(i); // do not advance: the next row shifts into index i
            } else {
                i++;
            }
        }
        return expectedRows.isEmpty() && actualRows.isEmpty();
    }

    /**
     * Index of the actual row that best corresponds to {@code expectedRow} (shares at least one matched-column
     * value), or -1 when no remaining actual row corresponds (then {@code expectedRow} is a genuine missing row).
     */
    private static int bestCorrespondingIndex(
            List<Map<String, Object>> actualRows,
            Map<String, Object> expectedRow,
            List<String> columns,
            JsonNode cellModifiers,
            GradingSupport support,
            boolean requireSharedValue) {
        int bestIndex = -1;
        // Multi-column rows must share >= 1 value to "correspond" (otherwise they are different rows ->
        // missing + extra). A single-column result has no identity column separate from its value, so the
        // lone row always corresponds and a difference is a CELL error (scalar/aggregate), never missing+extra.
        int bestScore = requireSharedValue ? 0 : -1;
        for (int i = 0; i < actualRows.size(); i++) {
            int score = 0;
            for (String column : columns) {
                Object actualValue = support.getRowValueIgnoreCase(actualRows.get(i), column);
                Object expectedValue = support.getRowValueIgnoreCase(expectedRow, column);
                if (support.valuesEqualByMatchTypeWithModifiers(actualValue, expectedValue, EXACT, cellModifiers, false, false)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int countRowOrderViolations(
            List<Map<String, Object>> actualRows,
            List<Map<String, Object>> expectedRows,
            List<String> columns,
            JsonNode cellModifiers,
            GradingSupport support) {
        List<String> actualSignatures = new ArrayList<>();
        for (Map<String, Object> row : actualRows) {
            actualSignatures.add(rowSignature(row, columns, cellModifiers, support));
        }
        List<String> expectedSignatures = new ArrayList<>();
        for (Map<String, Object> row : expectedRows) {
            expectedSignatures.add(rowSignature(row, columns, cellModifiers, support));
        }

        // Set must match (same multiset of rows) before order can be judged wrong.
        List<String> sortedActual = new ArrayList<>(actualSignatures);
        List<String> sortedExpected = new ArrayList<>(expectedSignatures);
        Collections.sort(sortedActual);
        Collections.sort(sortedExpected);
        if (!sortedActual.equals(sortedExpected)) {
            return 0;
        }

        int violations = 0;
        for (int i = 0; i < Math.min(actualSignatures.size(), expectedSignatures.size()); i++) {
            if (!actualSignatures.get(i).equals(expectedSignatures.get(i))) {
                violations++;
            }
        }
        return violations;
    }

    /** Row signature over the matched columns, modifier-aware so it agrees with cell-level EXACT comparison. */
    private static String rowSignature(Map<String, Object> row, List<String> columns, JsonNode cellModifiers, GradingSupport support) {
        StringBuilder signature = new StringBuilder();
        for (String column : columns) {
            String value = support.applyInsertModifiers(
                    support.normalizeValueStr(support.getRowValueIgnoreCase(row, column), false, false), cellModifiers);
            signature.append(value == null ? " " : value).append("|||");
        }
        return signature.toString();
    }
}
