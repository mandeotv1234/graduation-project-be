package graduation_project_be.application.usecases.grading.whitebox;

/**
 * Normalises SQL text before text-safe white-box checks run, so keywords inside comments, string
 * literals, or bracket identifiers do not cause false positives. Comments collapse to a single space
 * (never joining the tokens on either side); strings become {@code ''}; {@code [Ident]} becomes a
 * neutral placeholder so a column literally named {@code [SELECT]} is not mistaken for the keyword.
 */
public final class SqlTextPreprocessor {

    private SqlTextPreprocessor() {
    }

    public static String clean(String sql) {
        return cleanPreservingIdentifiers(sql)
                .replaceAll("\\[[^\\]]*\\]", "[id]");
    }

    /**
     * Strips comments and string literals while retaining identifiers for parameterized DDL rules.
     */
    public static String cleanPreservingIdentifiers(String sql) {
        if (sql == null) {
            return "";
        }
        String s = sql;
        s = s.replaceAll("/\\*[\\s\\S]*?\\*/", " "); // block comment -> space
        s = s.replaceAll("--[^\\n]*", " ");           // line comment -> space
        s = s.replaceAll("[Nn]?'(?:[^']|'')*'", "''"); // string / N'...' literal -> ''
        return s;
    }
}
