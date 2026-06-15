package graduation_project_be.application.usecases.grading.whitebox;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Case-insensitive regex detectors for FUNCTION and STORED_PROCEDURE white-box checks.
 * All patterns run on SQL already cleaned by {@link SqlTextPreprocessor}.
 */
public final class RoutineWhiteboxPatterns {

    private RoutineWhiteboxPatterns() {
    }

    // ---- FUNCTION ----
    static final Pattern RETURN_STMT = ci("\\bRETURN\\b");
    static final Pattern RETURNS_SCALAR = ci("\\bRETURNS\\s+(?!TABLE\\b)(?!@)\\w");
    static final Pattern RETURNS_TABLE = ci("\\bRETURNS\\s+(@\\w+\\s+)?TABLE\\b");
    static final Pattern RETURNS_KEYWORD = ci("\\bRETURNS\\s+(\\w[\\w\\s(),]*)");
    static final Pattern SCHEMABINDING = ci("\\bWITH\\s+SCHEMABINDING\\b");
    static final Pattern NONDETERMINISTIC_FN = ci("\\b(GETDATE|SYSDATETIME|GETUTCDATE|RAND|NEWID)\\s*\\(");
    static final Pattern DML_IN_FUNCTION = ci("\\b(INSERT|UPDATE|DELETE)\\b");

    // ---- STORED_PROCEDURE ----
    static final Pattern BEGIN_TRY = ci("\\bBEGIN\\s+TRY\\b");
    static final Pattern BEGIN_CATCH = ci("\\bBEGIN\\s+CATCH\\b");
    static final Pattern BEGIN_TRAN = ci("\\bBEGIN\\s+TRAN(SACTION)?\\b");
    static final Pattern COMMIT = ci("\\bCOMMIT\\b");
    static final Pattern ROLLBACK = ci("\\bROLLBACK\\b");
    static final Pattern SET_NOCOUNT_ON = ci("\\bSET\\s+NOCOUNT\\s+ON\\b");
    static final Pattern INPUT_VALIDATION = ci("\\bIF\\s+@\\w+\\s+IS\\s+(NOT\\s+)?NULL\\b");
    static final Pattern OUTPUT_PARAM = ci("@\\w+\\s+[\\w(),\\s]+\\s+OUT(PUT)?\\b");
    static final Pattern DDL_IN_PROC = ci("\\b(DROP|CREATE|ALTER)\\s+TABLE\\b");
    static final Pattern TRUNCATE_TABLE = ci("\\bTRUNCATE\\s+TABLE\\b");
    static final Pattern PRINT_STMT = ci("\\bPRINT\\b");
    static final Pattern RAISERROR_LEGACY = ci("\\bRAISERROR\\s*\\(");

    // ---- Shared (FUNCTION + STORED_PROCEDURE) ----
    static final Pattern CURSOR_DECLARE = ci("\\bDECLARE\\s+\\w+\\s+CURSOR\\b");
    static final Pattern DYNAMIC_SQL = ci("\\bEXEC\\s*\\(|\\bsp_executesql\\b");

    // ---- MAX_PARAM_COUNT: count @param declarations in the CREATE header ----
    static final Pattern PARAM_DECL = ci("@\\w+\\s+[\\w(),]+");

    private static Pattern ci(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    static boolean find(String sql, Pattern pattern) {
        return sql != null && pattern.matcher(sql).find();
    }

    static int count(String sql, Pattern pattern) {
        if (sql == null) return 0;
        Matcher m = pattern.matcher(sql);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * Returns the token immediately after RETURNS (the declared return type), or empty string
     * if RETURNS is absent or the return type cannot be extracted.
     */
    static String extractReturnType(String sql) {
        if (sql == null) return "";
        Matcher m = RETURNS_KEYWORD.matcher(sql);
        if (!m.find()) return "";
        String fragment = m.group(1).trim();
        // Take only the first word (data type keyword)
        int space = fragment.indexOf(' ');
        return (space > 0 ? fragment.substring(0, space) : fragment).toUpperCase();
    }

    /**
     * Counts @param declarations that appear in the CREATE header (before AS/BEGIN).
     * Stops scanning after the first AS or BEGIN keyword to avoid counting local variables.
     */
    static int countParameters(String sql) {
        if (sql == null) return 0;
        // Truncate at AS/BEGIN to limit scope to header
        int asIdx = indexOfKeyword(sql, "AS");
        int beginIdx = indexOfKeyword(sql, "BEGIN");
        int cutoff = -1;
        if (asIdx >= 0) cutoff = asIdx;
        if (beginIdx >= 0 && (cutoff < 0 || beginIdx < cutoff)) cutoff = beginIdx;
        String header = cutoff > 0 ? sql.substring(0, cutoff) : sql;
        return count(header, PARAM_DECL);
    }

    private static int indexOfKeyword(String sql, String kw) {
        Matcher m = Pattern.compile("\\b" + kw + "\\b", Pattern.CASE_INSENSITIVE).matcher(sql);
        return m.find() ? m.start() : -1;
    }
}
