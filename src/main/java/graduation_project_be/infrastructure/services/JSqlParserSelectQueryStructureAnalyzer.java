package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.SelectQueryStructureAnalyzer;
import graduation_project_be.application.usecases.grading.QueryStructureFacts;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * JSQLParser-backed structural analyzer for T-SQL SELECT statements.
 *
 * <p>Square-bracket identifiers ([col]) are enabled for SQL Server dialect. Any failure to
 * parse (dialect gap, syntax error, non-SELECT) returns {@link QueryStructureFacts#parseFailed()}
 * so the grader degrades to black-box comparison instead of penalising the student.
 */
public class JSqlParserSelectQueryStructureAnalyzer implements SelectQueryStructureAnalyzer {

    private static final Set<String> AGGREGATES = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");

    @Override
    public QueryStructureFacts analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return QueryStructureFacts.parseFailed();
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql, p -> p.withSquareBracketQuotation(true));
            if (!(statement instanceof Select select)) {
                return QueryStructureFacts.parseFailed();
            }

            boolean hasCte = notEmpty(select.getWithItemsList());
            boolean hasOrderBy = notEmpty(select.getOrderByElements());
            PlainSelect ps = select.getPlainSelect();
            if (ps == null) {
                // Set operation (UNION/INTERSECT/EXCEPT): only top-level facts are reliable.
                return new QueryStructureFacts(true, 0, 0, false, false, false, false,
                        hasCte, false, hasOrderBy, false, Set.of(), 0, false, false);
            }

            int joinCount = 0;
            int fromTableCount = 1;
            boolean subqueryInFrom = isSubselect(ps.getFromItem());
            Acc joinAcc = new Acc();
            List<Join> joins = ps.getJoins();
            if (joins != null) {
                for (Join join : joins) {
                    fromTableCount++;
                    if (!join.isSimple()) {
                        joinCount++;
                    }
                    if (isSubselect(join.getRightItem())) {
                        subqueryInFrom = true;
                    }
                    // ON conditions can carry subqueries/aggregates that drive REQUIRE_* and nesting depth.
                    if (join.getOnExpressions() != null) {
                        for (Expression on : join.getOnExpressions()) {
                            walk(on, 0, joinAcc, false);
                        }
                    }
                }
            }

            Acc selectAcc = new Acc();
            if (ps.getSelectItems() != null) {
                for (SelectItem<?> item : ps.getSelectItems()) {
                    walk(item.getExpression(), 0, selectAcc, false);
                }
            }
            Acc whereAcc = new Acc();
            walk(ps.getWhere(), 0, whereAcc, true);
            Acc havingAcc = new Acc();
            walk(ps.getHaving(), 0, havingAcc, false);

            Set<String> aggregates = new TreeSet<>();
            aggregates.addAll(selectAcc.aggregates);
            aggregates.addAll(havingAcc.aggregates);
            aggregates.addAll(joinAcc.aggregates);

            int maxDepth = Math.max(
                    Math.max(Math.max(selectAcc.maxDepth, whereAcc.maxDepth), joinAcc.maxDepth),
                    Math.max(havingAcc.maxDepth, subqueryInFrom ? 1 : 0));

            return new QueryStructureFacts(
                    true,
                    joinCount,
                    fromTableCount,
                    selectAcc.subselect,
                    subqueryInFrom,
                    whereAcc.subselect,
                    havingAcc.subselect,
                    hasCte,
                    ps.getGroupBy() != null,
                    hasOrderBy,
                    ps.getDistinct() != null,
                    aggregates,
                    maxDepth,
                    whereAcc.hasLiteral,
                    hasCorrelatedSubquery(ps, Set.of()));
        } catch (Throwable t) {
            return QueryStructureFacts.parseFailed();
        }
    }

    /**
     * A subquery is correlated when it references a table/alias declared in an enclosing query but
     * not in its own FROM. Detection is precision-biased (only flags an explicit table-qualified
     * column whose prefix resolves to an outer scope), so an undetected exotic case is treated as
     * not-correlated rather than penalising a fair answer. Only expression-position subqueries
     * (SELECT list, WHERE, HAVING, JOIN ON) are inspected; derived tables in FROM are not.
     */
    private boolean hasCorrelatedSubquery(PlainSelect ps, Set<String> enclosingAliases) {
        if (ps == null) {
            return false;
        }
        Set<String> visible = new HashSet<>(enclosingAliases);
        visible.addAll(declaredAliases(ps));

        List<Select> subSelects = new ArrayList<>();
        if (ps.getSelectItems() != null) {
            for (SelectItem<?> item : ps.getSelectItems()) {
                collectSubSelects(item.getExpression(), subSelects);
            }
        }
        collectSubSelects(ps.getWhere(), subSelects);
        collectSubSelects(ps.getHaving(), subSelects);
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        collectSubSelects(on, subSelects);
                    }
                }
            }
        }

        for (Select sub : subSelects) {
            PlainSelect subPs = sub.getPlainSelect();
            if (subPs == null) {
                continue;
            }
            Set<String> subOwn = declaredAliases(subPs);
            if (referencesOuterScope(subPs, visible, subOwn)) {
                return true;
            }
            // A nested subquery may correlate to any of the scopes above it.
            if (hasCorrelatedSubquery(subPs, visible)) {
                return true;
            }
        }
        return false;
    }

    /** True if any column directly in {@code subPs}'s clauses is qualified by an outer-scope alias. */
    private boolean referencesOuterScope(PlainSelect subPs, Set<String> outer, Set<String> subOwn) {
        List<Column> columns = new ArrayList<>();
        if (subPs.getSelectItems() != null) {
            for (SelectItem<?> item : subPs.getSelectItems()) {
                collectColumns(item.getExpression(), columns);
            }
        }
        collectColumns(subPs.getWhere(), columns);
        collectColumns(subPs.getHaving(), columns);
        if (subPs.getJoins() != null) {
            for (Join join : subPs.getJoins()) {
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        collectColumns(on, columns);
                    }
                }
            }
        }
        for (Column column : columns) {
            if (column.getTable() == null) {
                continue;
            }
            String prefix = normalizeAlias(column.getTable().getName());
            if (prefix != null && outer.contains(prefix) && !subOwn.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Alias names (or bare table names when unaliased) declared in a query's FROM and JOINs. */
    private Set<String> declaredAliases(PlainSelect ps) {
        Set<String> names = new HashSet<>();
        addFromItemAlias(ps.getFromItem(), names);
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                addFromItemAlias(join.getRightItem(), names);
            }
        }
        return names;
    }

    private void addFromItemAlias(FromItem fromItem, Set<String> names) {
        if (fromItem == null) {
            return;
        }
        if (fromItem.getAlias() != null && fromItem.getAlias().getName() != null) {
            String alias = normalizeAlias(fromItem.getAlias().getName());
            if (alias != null) {
                names.add(alias);
            }
        }
        if (fromItem instanceof net.sf.jsqlparser.schema.Table table && table.getName() != null) {
            String name = normalizeAlias(table.getName());
            if (name != null) {
                names.add(name);
            }
        }
    }

    private static String normalizeAlias(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.replace("[", "").replace("]", "").trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int dot = trimmed.lastIndexOf('.');
        if (dot >= 0 && dot < trimmed.length() - 1) {
            trimmed = trimmed.substring(dot + 1);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    /** Collects nested {@link Select} nodes directly inside an expression (does not descend into them). */
    private void collectSubSelects(Expression expr, List<Select> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Select select) {
            out.add(select);
        } else if (expr instanceof Parenthesis paren) {
            collectSubSelects(paren.getExpression(), out);
        } else if (expr instanceof NotExpression not) {
            collectSubSelects(not.getExpression(), out);
        } else if (expr instanceof SignedExpression signed) {
            collectSubSelects(signed.getExpression(), out);
        } else if (expr instanceof Between between) {
            collectSubSelects(between.getLeftExpression(), out);
            collectSubSelects(between.getBetweenExpressionStart(), out);
            collectSubSelects(between.getBetweenExpressionEnd(), out);
        } else if (expr instanceof InExpression in) {
            collectSubSelects(in.getLeftExpression(), out);
            collectSubSelects(in.getRightExpression(), out);
        } else if (expr instanceof ExistsExpression exists) {
            collectSubSelects(exists.getRightExpression(), out);
        } else if (expr instanceof CaseExpression caseExpr) {
            collectSubSelects(caseExpr.getSwitchExpression(), out);
            if (caseExpr.getWhenClauses() != null) {
                for (WhenClause when : caseExpr.getWhenClauses()) {
                    collectSubSelects(when.getWhenExpression(), out);
                    collectSubSelects(when.getThenExpression(), out);
                }
            }
            collectSubSelects(caseExpr.getElseExpression(), out);
        } else if (expr instanceof Function fn && fn.getParameters() != null) {
            for (Object p : fn.getParameters()) {
                if (p instanceof Expression pe) {
                    collectSubSelects(pe, out);
                }
            }
        } else if (expr instanceof BinaryExpression bin) {
            collectSubSelects(bin.getLeftExpression(), out);
            collectSubSelects(bin.getRightExpression(), out);
        }
    }

    /** Collects {@link Column} references in an expression, without descending into nested subqueries. */
    private void collectColumns(Expression expr, List<Column> out) {
        if (expr == null || expr instanceof Select) {
            return;
        }
        if (expr instanceof Column column) {
            out.add(column);
        } else if (expr instanceof Parenthesis paren) {
            collectColumns(paren.getExpression(), out);
        } else if (expr instanceof NotExpression not) {
            collectColumns(not.getExpression(), out);
        } else if (expr instanceof SignedExpression signed) {
            collectColumns(signed.getExpression(), out);
        } else if (expr instanceof Between between) {
            collectColumns(between.getLeftExpression(), out);
            collectColumns(between.getBetweenExpressionStart(), out);
            collectColumns(between.getBetweenExpressionEnd(), out);
        } else if (expr instanceof InExpression in) {
            collectColumns(in.getLeftExpression(), out);
            collectColumns(in.getRightExpression(), out);
        } else if (expr instanceof ExistsExpression exists) {
            collectColumns(exists.getRightExpression(), out);
        } else if (expr instanceof CaseExpression caseExpr) {
            collectColumns(caseExpr.getSwitchExpression(), out);
            if (caseExpr.getWhenClauses() != null) {
                for (WhenClause when : caseExpr.getWhenClauses()) {
                    collectColumns(when.getWhenExpression(), out);
                    collectColumns(when.getThenExpression(), out);
                }
            }
            collectColumns(caseExpr.getElseExpression(), out);
        } else if (expr instanceof Function fn && fn.getParameters() != null) {
            for (Object p : fn.getParameters()) {
                if (p instanceof Expression pe) {
                    collectColumns(pe, out);
                }
            }
        } else if (expr instanceof BinaryExpression bin) {
            collectColumns(bin.getLeftExpression(), out);
            collectColumns(bin.getRightExpression(), out);
        }
    }

    /** Mutable accumulator for a single clause subtree walk. */
    private static final class Acc {
        boolean subselect;
        boolean hasLiteral;
        int maxDepth;
        final Set<String> aggregates = new TreeSet<>();
    }

    private static boolean isSubselect(FromItem fromItem) {
        return fromItem instanceof Select;
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /** Recursively scan an expression subtree, accumulating structural facts. */
    private void walk(Expression expr, int depth, Acc acc, boolean trackLiteral) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Select nested) {
            acc.subselect = true;
            acc.maxDepth = Math.max(acc.maxDepth, depth + 1);
            scanInner(nested, depth + 1, acc);
        } else if (expr instanceof Parenthesis paren) {
            walk(paren.getExpression(), depth, acc, trackLiteral);
        } else if (expr instanceof NotExpression not) {
            walk(not.getExpression(), depth, acc, trackLiteral);
        } else if (expr instanceof Function fn) {
            String name = fn.getName();
            if (name != null && AGGREGATES.contains(name.toUpperCase(Locale.ROOT))) {
                acc.aggregates.add(name.toUpperCase(Locale.ROOT));
            }
            ExpressionList<?> params = fn.getParameters();
            if (params != null) {
                for (Object p : params) {
                    if (p instanceof Expression pe) {
                        walk(pe, depth, acc, trackLiteral);
                    }
                }
            }
        } else if (expr instanceof InExpression in) {
            walk(in.getLeftExpression(), depth, acc, trackLiteral);
            walk(in.getRightExpression(), depth, acc, trackLiteral);
        } else if (expr instanceof ExistsExpression exists) {
            walk(exists.getRightExpression(), depth, acc, trackLiteral);
        } else if (expr instanceof Between between) {
            walk(between.getLeftExpression(), depth, acc, trackLiteral);
            walk(between.getBetweenExpressionStart(), depth, acc, trackLiteral);
            walk(between.getBetweenExpressionEnd(), depth, acc, trackLiteral);
        } else if (expr instanceof SignedExpression signed) {
            walk(signed.getExpression(), depth, acc, trackLiteral);
        } else if (expr instanceof CaseExpression caseExpr) {
            walk(caseExpr.getSwitchExpression(), depth, acc, trackLiteral);
            if (caseExpr.getWhenClauses() != null) {
                for (WhenClause when : caseExpr.getWhenClauses()) {
                    walk(when.getWhenExpression(), depth, acc, trackLiteral);
                    walk(when.getThenExpression(), depth, acc, trackLiteral);
                }
            }
            walk(caseExpr.getElseExpression(), depth, acc, trackLiteral);
        } else if (expr instanceof BinaryExpression bin) {
            walk(bin.getLeftExpression(), depth, acc, trackLiteral);
            walk(bin.getRightExpression(), depth, acc, trackLiteral);
        } else if (trackLiteral && isLiteral(expr) && !isAllowlistedLiteral(expr)) {
            acc.hasLiteral = true;
        }
    }

    /** Descend into a nested subquery to find deeper subselects and aggregates. */
    private void scanInner(Select select, int depth, Acc acc) {
        PlainSelect ps = select.getPlainSelect();
        if (ps == null) {
            return;
        }
        if (ps.getSelectItems() != null) {
            for (SelectItem<?> item : ps.getSelectItems()) {
                walk(item.getExpression(), depth, acc, false);
            }
        }
        walk(ps.getWhere(), depth, acc, false);
        walk(ps.getHaving(), depth, acc, false);
        if (isSubselect(ps.getFromItem())) {
            acc.maxDepth = Math.max(acc.maxDepth, depth + 1);
            scanInner((Select) ps.getFromItem(), depth + 1, acc);
        }
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                if (isSubselect(join.getRightItem())) {
                    acc.maxDepth = Math.max(acc.maxDepth, depth + 1);
                    scanInner((Select) join.getRightItem(), depth + 1, acc);
                }
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        walk(on, depth, acc, false);
                    }
                }
            }
        }
    }

    private static boolean isLiteral(Expression expr) {
        return expr instanceof StringValue || expr instanceof LongValue || expr instanceof DoubleValue
                || expr instanceof DateValue || expr instanceof TimeValue || expr instanceof TimestampValue;
    }

    /**
     * Constants that are not evidence of a hardcoded answer and so are excluded from the
     * "literal in WHERE" fact: the 0/1 flags, empty strings, Unicode {@code N'...'} strings, and
     * date/time literals. Only "magic" literals (e.g. {@code = 12345}, {@code = 'AnswerText'})
     * remain flaggable, keeping the opt-in FORBID_LITERAL_IN_WHERE check from firing on fair queries.
     */
    private static boolean isAllowlistedLiteral(Expression expr) {
        if (expr instanceof LongValue lv) {
            long value = lv.getValue();
            return value == 0L || value == 1L;
        }
        if (expr instanceof StringValue sv) {
            String prefix = sv.getPrefix();
            if (prefix != null && !prefix.isBlank()) {
                return true;
            }
            return sv.getValue() == null || sv.getValue().isEmpty();
        }
        return expr instanceof DateValue || expr instanceof TimeValue || expr instanceof TimestampValue;
    }
}
