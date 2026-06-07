package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.SelectQueryStructureAnalyzer;
import graduation_project_be.application.usecases.grading.QueryStructureFacts;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
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
                return new QueryStructureFacts(true, 0, 0, false, false, false,
                        hasCte, false, hasOrderBy, false, Set.of(), 0, false);
            }

            int joinCount = 0;
            int fromTableCount = 1;
            boolean subqueryInFrom = isSubselect(ps.getFromItem());
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

            int maxDepth = Math.max(Math.max(selectAcc.maxDepth, whereAcc.maxDepth),
                    Math.max(havingAcc.maxDepth, subqueryInFrom ? 1 : 0));

            return new QueryStructureFacts(
                    true,
                    joinCount,
                    fromTableCount,
                    selectAcc.subselect,
                    subqueryInFrom,
                    whereAcc.subselect,
                    hasCte,
                    ps.getGroupBy() != null,
                    hasOrderBy,
                    ps.getDistinct() != null,
                    aggregates,
                    maxDepth,
                    whereAcc.hasLiteral);
        } catch (Throwable t) {
            return QueryStructureFacts.parseFailed();
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
        } else if (expr instanceof BinaryExpression bin) {
            walk(bin.getLeftExpression(), depth, acc, trackLiteral);
            walk(bin.getRightExpression(), depth, acc, trackLiteral);
        } else if (trackLiteral && isLiteral(expr)) {
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
            }
        }
    }

    private static boolean isLiteral(Expression expr) {
        return expr instanceof StringValue || expr instanceof LongValue || expr instanceof DoubleValue
                || expr instanceof DateValue || expr instanceof TimeValue || expr instanceof TimestampValue;
    }
}
