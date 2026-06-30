package graduation_project_be.application.usecases.grading;

import graduation_project_be.application.usecases.grading.SelectDatasetAdequacyLinter.CheckId;
import graduation_project_be.application.usecases.grading.SelectDatasetAdequacyLinter.Finding;
import graduation_project_be.application.usecases.grading.SelectDatasetAdequacyLinter.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure coverage for the authoring data-adequacy lint: the cheap checks that run on the teacher
 * reference rows already produced by the rubric-testing sandbox (no DB, no Spring context). The
 * boolean flags stand in for the correct query's shape (aggregate present / GROUP BY present).
 */
class SelectDatasetAdequacyLinterTest {

    private final SelectDatasetAdequacyLinter linter = new SelectDatasetAdequacyLinter();

    @SafeVarargs
    private List<Map<String, Object>> rows(Map<String, Object>... rows) {
        return new ArrayList<>(List.of(rows));
    }

    private Map<String, Object> row(Object... keyValues) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            r.put((String) keyValues[i], keyValues[i + 1]);
        }
        return r;
    }

    private boolean has(List<Finding> findings, CheckId id) {
        return findings.stream().anyMatch(f -> f.checkId() == id);
    }

    // --- #1 non-empty reference (hard warn) ---

    @Test
    void emptyReferenceIsTheOnlyFindingAndHardWarn() {
        List<Finding> findings = linter.lint(List.of(), false, false);
        assertEquals(1, findings.size(), "empty reference short-circuits the other checks");
        assertEquals(CheckId.EMPTY_REF, findings.get(0).checkId());
        assertEquals(Severity.HARD_WARN, findings.get(0).severity());
    }

    @Test
    void nullRowsTreatedAsEmptyReference() {
        List<Finding> findings = linter.lint(null, false, false);
        assertEquals(1, findings.size());
        assertEquals(CheckId.EMPTY_REF, findings.get(0).checkId());
    }

    // --- #2 at least two rows ---

    @Test
    void singleRowOfARowSetQueryFlaggedAsTooFewRows() {
        List<Finding> findings = linter.lint(rows(row("id", 1, "name", "a")), false, false);
        assertEquals(1, findings.size());
        assertEquals(CheckId.TOO_FEW_ROWS, findings.get(0).checkId());
        assertEquals(Severity.WARN, findings.get(0).severity());
    }

    @Test
    void scalarAggregateSingleRowIsNotFlagged() {
        // SELECT COUNT(*) AS cnt ... (aggregate, no GROUP BY): one row is the correct shape.
        List<Finding> findings = linter.lint(rows(row("cnt", 7)), true, false);
        assertTrue(findings.isEmpty(), "scalar aggregate must not be warned for returning one row");
    }

    @Test
    void groupedAggregateStillRequiresTwoRows() {
        // SELECT dept, COUNT(*) ... GROUP BY dept: returns a row set, so a single group is still thin.
        assertTrue(has(linter.lint(rows(row("dept", "A", "n", 3)), true, true), CheckId.TOO_FEW_ROWS));
    }

    @Test
    void twoRowsDoNotTriggerTooFewRows() {
        assertTrue(linter.lint(rows(row("id", 1), row("id", 2)), false, false).isEmpty());
    }

    // --- #4 column NULL diversity ---

    @Test
    void columnThatIsAllNullIsFlaggedWithItsName() {
        List<Finding> findings = linter.lint(
                rows(row("id", 1, "email", null), row("id", 2, "email", null)), false, false);
        assertEquals(1, findings.size());
        assertEquals(CheckId.COLUMN_ALL_NULL, findings.get(0).checkId());
        assertTrue(findings.get(0).message().contains("email"), "message must name the degenerate column");
    }

    @Test
    void allNullColumnNotFlaggedWhenQueryAggregates() {
        // A NULL aggregate column (e.g. SUM over NULL) is an intentional shape, not a weak dataset.
        List<Finding> findings = linter.lint(
                rows(row("dept", "A", "total", null), row("dept", "B", "total", null)), true, true);
        assertTrue(findings.isEmpty(), "aggregate column may be NULL by design -> no COLUMN_ALL_NULL");
    }

    @Test
    void healthyRowSetProducesNoFindings() {
        List<Finding> findings = linter.lint(
                rows(row("id", 1, "name", "a"), row("id", 2, "name", null)), false, false);
        assertTrue(findings.isEmpty(), "2+ rows, every column has a real value -> no findings");
    }
}
