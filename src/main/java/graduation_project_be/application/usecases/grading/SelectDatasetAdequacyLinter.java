package graduation_project_be.application.usecases.grading;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authoring-time data-adequacy lint for SELECT trap datasets.
 *
 * <p>Runs cheap deterministic checks on the rows the teacher's reference query already produced on a
 * test-case dataset (collected by the rubric-testing sandbox). It never touches the database itself
 * and never blocks publishing — it only tells the teacher when a dataset is too degenerate to
 * discriminate a wrong query: an empty reference, a single trivial row, or a selected column that is
 * NULL in every row.
 *
 * <p>The row-count and column-NULL checks operate on the teacher's <em>output</em> rows, so they are
 * only meaningful for queries that return a row set. They are therefore skipped for scalar-aggregate
 * queries ({@code COUNT}/{@code SUM}/... without {@code GROUP BY}), whose single — possibly NULL —
 * output row is the correct shape rather than a weak dataset; and the column-NULL check is skipped
 * whenever an aggregate is present, because an aggregate column may legitimately be NULL (an
 * intentional {@code SUM}-over-NULL trap). NULL diversity of the underlying data cannot be inferred
 * from aggregate output, so it is not checked here.
 *
 * <p>Discrimination of JOIN/LIKE mistakes is handled separately by
 * {@link SelectTrapDiscriminationChecker}; this linter only covers the structural-shape checks that
 * are cheap and sound to compute from the reference rows.
 */
public class SelectDatasetAdequacyLinter {

    /** How strongly the teacher should react. Both are advisory — neither blocks publishing. */
    public enum Severity {
        /** Dataset is effectively unusable (empty reference). */
        HARD_WARN,
        /** Dataset still grades but is weak at catching mistakes. */
        WARN
    }

    /** Which check produced a finding (stable identifier for the UI and tests). */
    public enum CheckId {
        EMPTY_REF,
        TOO_FEW_ROWS,
        COLUMN_ALL_NULL
    }

    /** A single advisory result; {@code message} is teacher-facing Vietnamese without a case prefix. */
    public record Finding(CheckId checkId, Severity severity, String message) {
    }

    /**
     * @param teacherRows      the reference result of the teacher's correct query on the trap dataset
     * @param aggregatePresent true when the correct query uses an aggregate function
     *                         (COUNT/SUM/AVG/MIN/MAX); the column-NULL check is skipped because an
     *                         aggregate column may be NULL by design
     * @param groupByPresent   true when the correct query has a GROUP BY; combined with
     *                         {@code aggregatePresent} it distinguishes a grouped query (returns a
     *                         row set) from a scalar aggregate (returns exactly one row)
     */
    public List<Finding> lint(List<Map<String, Object>> teacherRows, boolean aggregatePresent, boolean groupByPresent) {
        List<Finding> findings = new ArrayList<>();

        // #1 non-empty reference: an empty reference cannot grade anything, so it stands alone. This
        // holds for every query shape — even a scalar aggregate returns one row.
        if (teacherRows == null || teacherRows.isEmpty()) {
            findings.add(new Finding(CheckId.EMPTY_REF, Severity.HARD_WARN,
                    "Đáp án chạy trên dữ liệu test case ra 0 dòng — dữ liệu rỗng, không chấm được. "
                            + "Hãy thêm dữ liệu để đáp án trả về ít nhất 1 dòng."));
            return findings;
        }

        // A scalar aggregate (aggregate without GROUP BY) legitimately returns exactly one row, so the
        // output-shape heuristics below do not apply to it.
        boolean scalarAggregate = aggregatePresent && !groupByPresent;

        // #2 at least two rows: a single row cannot exercise GROUP BY / DISTINCT / a WHERE filter —
        // but only for queries that are meant to return a row set, not a scalar aggregate.
        if (!scalarAggregate && teacherRows.size() < 2) {
            findings.add(new Finding(CheckId.TOO_FEW_ROWS, Severity.WARN,
                    "Đáp án chỉ trả về 1 dòng — quá ít để kiểm tra GROUP BY / DISTINCT / điều kiện lọc. "
                            + "Nên có ít nhất 2 dòng kết quả."));
        }

        // #4 column diversity: a selected column that is NULL in every row never gets a real value to
        // compare. Skipped when an aggregate is present, because an aggregate column may be NULL by
        // design (e.g. an intentional SUM-over-NULL trap).
        if (!aggregatePresent) {
            for (String column : collectColumns(teacherRows)) {
                boolean allNull = true;
                for (Map<String, Object> rowData : teacherRows) {
                    if (rowData != null && rowData.get(column) != null) {
                        allNull = false;
                        break;
                    }
                }
                if (allNull) {
                    findings.add(new Finding(CheckId.COLUMN_ALL_NULL, Severity.WARN,
                            "Cột '" + column + "' toàn NULL ở mọi dòng kết quả — không kiểm được giá trị cột này. "
                                    + "Hãy thêm dữ liệu có giá trị thật cho cột này."));
                }
            }
        }

        return findings;
    }

    /** Union of column names across all rows (a NULL cell may be absent as a key in some drivers). */
    private Set<String> collectColumns(List<Map<String, Object>> teacherRows) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> rowData : teacherRows) {
            if (rowData != null) {
                columns.addAll(rowData.keySet());
            }
        }
        return columns;
    }
}
