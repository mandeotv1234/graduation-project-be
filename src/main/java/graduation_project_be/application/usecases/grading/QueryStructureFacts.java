package graduation_project_be.application.usecases.grading;

import java.util.Set;

/**
 * Immutable structural facts about a student's SELECT statement, derived from its parse tree.
 *
 * <p>White-box grading rules (target = QUERY) are evaluated against these facts. When
 * {@code parseOk} is false the query could not be parsed; callers must skip structural rules
 * and grade black-box instead (fail-open), never auto-penalise.
 */
public record QueryStructureFacts(
        boolean parseOk,
        int joinCount,            // explicit JOIN keywords (comma-joins excluded)
        int fromTableCount,       // table sources in FROM (1 + joins); >1 means comma-join present
        boolean hasSubqueryInSelect,
        boolean hasSubqueryInFrom,
        boolean hasSubqueryInWhere,
        boolean hasCte,
        boolean hasGroupBy,
        boolean hasOrderBy,
        boolean hasDistinct,
        Set<String> aggregateFns, // upper-cased: COUNT/SUM/AVG/MIN/MAX present in the query
        int maxNestingDepth,      // 0 = no nested subquery, 1 = one level, ...
        boolean hasLiteralInWhere) {

    /** Facts for a query that could not be parsed; all structural checks must be skipped. */
    public static QueryStructureFacts parseFailed() {
        return new QueryStructureFacts(
                false, 0, 0, false, false, false, false, false, false, false, Set.of(), 0, false);
    }
}
