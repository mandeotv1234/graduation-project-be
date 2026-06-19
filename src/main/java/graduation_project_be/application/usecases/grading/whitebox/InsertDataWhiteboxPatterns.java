package graduation_project_be.application.usecases.grading.whitebox;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Case-insensitive detectors for INSERT_DATA white-box checks. Patterns run on SQL already cleaned
 * by {@link SqlTextPreprocessor}, so comments, string literals and bracket identifiers are neutral.
 */
public final class InsertDataWhiteboxPatterns {

    private InsertDataWhiteboxPatterns() {
    }

    private static final String IDENT = "(?:\\[id\\]|[#@]?[A-Za-z_][A-Za-z0-9_#$@]*)";
    private static final String OBJECT_NAME = IDENT + "(?:\\s*\\.\\s*" + IDENT + "){0,2}";

    static final Pattern NOCHECK_CONSTRAINT = ci("\\bNOCHECK\\s+CONSTRAINT\\b");
    static final Pattern SET_IDENTITY_INSERT_ON = ci("\\bSET\\s+IDENTITY_INSERT\\s+"
            + OBJECT_NAME + "\\s+ON\\b");
    static final Pattern DISABLE_TRIGGER = ci("\\bDISABLE\\s+TRIGGER\\b");
    static final Pattern INSERT_INTO_TARGET = ci("\\bINSERT\\s+INTO\\s+" + OBJECT_NAME);
    static final Pattern INSERT_SELECT = ci("\\bINSERT\\s+INTO\\s+" + OBJECT_NAME
            + "\\s*(?:\\([^)]*\\)\\s*)?SELECT\\b");
    static final Pattern TRUNCATE_TABLE = ci("\\bTRUNCATE\\s+TABLE\\b");
    static final Pattern UPDATE_DELETE = ci("\\bUPDATE\\s+(?!STATISTICS\\b)|\\bDELETE\\s+(?:FROM\\s+)?");
    static final Pattern MERGE = ci("\\bMERGE\\s+(?:INTO\\s+)?");
    static final Pattern STATEMENT_START = ci("\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+(?:FROM\\s+)?|MERGE|"
            + "TRUNCATE\\s+TABLE|ALTER\\s+TABLE|SET\\s+IDENTITY_INSERT|DISABLE\\s+TRIGGER)\\b");

    private static Pattern ci(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    static boolean find(String sql, Pattern pattern) {
        return sql != null && pattern.matcher(sql).find();
    }

    static String firstMatch(String sql, Pattern pattern) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(sql);
        return matcher.find() ? matcher.group().trim() : null;
    }

    static int count(String sql, Pattern pattern) {
        if (sql == null) {
            return 0;
        }
        Matcher matcher = pattern.matcher(sql);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    static ColumnListCheck columnListCheck(String sql) {
        if (sql == null || sql.isBlank()) {
            return new ColumnListCheck(0, 0, null);
        }
        Matcher matcher = INSERT_INTO_TARGET.matcher(sql);
        int insertCount = 0;
        int missingCount = 0;
        String firstMissing = null;
        while (matcher.find()) {
            insertCount++;
            int next = skipWhitespace(sql, matcher.end());
            boolean hasColumnList = next < sql.length() && sql.charAt(next) == '(';
            if (!hasColumnList) {
                missingCount++;
                if (firstMissing == null) {
                    firstMissing = matcher.group().trim();
                }
            }
        }
        return new ColumnListCheck(insertCount, missingCount, firstMissing);
    }

    private static int skipWhitespace(String sql, int index) {
        int i = index;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    record ColumnListCheck(int insertCount, int missingCount, String firstMissing) {
    }
}
