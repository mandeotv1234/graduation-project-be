package graduation_project_be.shared.utils;

import java.util.Locale;

/**
 * Detects subqueries per SQL clause.
 *
 * Forbidden positions: SELECT list, FROM clause.
 * Allowed positions  : WHERE, HAVING (e.g. correlated filters, EXISTS).
 *
 * Usage:
 *   var result = SqlWhiteboxAnalyzer.analyzeSubqueries(studentSql);
 *   if (result.hasForbiddenInSelect()) { ... }
 */
public class SqlWhiteboxAnalyzer {

    public enum Clause { SELECT_LIST, FROM_CLAUSE, WHERE, HAVING, OTHER }

    public record SubqueryAnalysisResult(
            int subqueriesInSelect,
            int subqueriesInFrom,
            int subqueriesInWhere,
            int subqueriesInHaving) {

        public boolean hasForbiddenInSelect() { return subqueriesInSelect > 0; }
        public boolean hasForbiddenInFrom()   { return subqueriesInFrom   > 0; }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    public static SubqueryAnalysisResult analyzeSubqueries(String sql) {
        if (sql == null || sql.isBlank()) {
            return new SubqueryAnalysisResult(0, 0, 0, 0);
        }

        String normalized = stripCommentsAndStrings(sql);
        int inSelect = 0, inFrom = 0, inWhere = 0, inHaving = 0;
        int depth = 0;
        Clause currentClause = Clause.OTHER;
        int len = normalized.length();
        int i = 0;

        while (i < len) {
            char c = normalized.charAt(i);

            if (c == '(') {
                depth++;
                // Peek past whitespace for SELECT / WITH — marks a subquery
                int j = i + 1;
                while (j < len && Character.isWhitespace(normalized.charAt(j))) j++;
                String nextWord = extractWord(normalized, j);
                if ("SELECT".equalsIgnoreCase(nextWord) || "WITH".equalsIgnoreCase(nextWord)) {
                    switch (currentClause) {
                        case SELECT_LIST -> inSelect++;
                        case FROM_CLAUSE -> inFrom++;
                        case WHERE       -> inWhere++;
                        case HAVING      -> inHaving++;
                        default          -> { /* ORDER BY, GROUP BY — ignored */ }
                    }
                }
                i++;
                continue;
            }

            if (c == ')') {
                if (depth > 0) depth--;
                i++;
                continue;
            }

            // Clause keyword detection only at depth 0 to avoid interference
            // from subquery internals.
            if (depth == 0 && Character.isLetter(c)) {
                String word = extractWord(normalized, i);
                Clause detected = detectClause(word);
                if (detected != null) {
                    currentClause = detected;
                    i += word.length();
                    continue;
                }
            }

            i++;
        }

        return new SubqueryAnalysisResult(inSelect, inFrom, inWhere, inHaving);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static Clause detectClause(String word) {
        return switch (word.toUpperCase(Locale.ROOT)) {
            case "SELECT"                          -> Clause.SELECT_LIST;
            case "FROM"                            -> Clause.FROM_CLAUSE;
            case "WHERE"                           -> Clause.WHERE;
            case "HAVING"                          -> Clause.HAVING;
            // GROUP BY / ORDER BY / set operators — reset context so we do not
            // mis-attribute a subquery appearing after them to a prior clause.
            case "GROUP", "ORDER", "UNION",
                 "INTERSECT", "EXCEPT"             -> Clause.OTHER;
            default                                -> null; // not a clause boundary
        };
    }

    private static String extractWord(String s, int pos) {
        int len = s.length();
        if (pos >= len || !Character.isLetter(s.charAt(pos))) return "";
        int start = pos;
        while (pos < len && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
            pos++;
        }
        return s.substring(start, pos);
    }

    /**
     * Replace string literals and comments with spaces so that SQL keywords
     * inside them do not confuse clause detection.
     */
    private static String stripCommentsAndStrings(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int len = sql.length();

        while (i < len) {
            char c = sql.charAt(i);

            // Single-line comment: -- …\n
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                while (i < len && sql.charAt(i) != '\n') i++;
                sb.append(' ');
                continue;
            }

            // Block comment: /* … */
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                if (i + 1 < len) i += 2;
                sb.append(' ');
                continue;
            }

            // String literal: '...'  ('' is an escaped single-quote inside)
            if (c == '\'') {
                i++;
                while (i < len) {
                    if (sql.charAt(i) == '\'') {
                        i++;
                        if (i < len && sql.charAt(i) == '\'') {
                            i++; // escaped ''
                        } else {
                            break; // end of literal
                        }
                    } else {
                        i++;
                    }
                }
                sb.append("''"); // placeholder preserves paren-depth accounting
                continue;
            }

            sb.append(c);
            i++;
        }

        return sb.toString();
    }
}
