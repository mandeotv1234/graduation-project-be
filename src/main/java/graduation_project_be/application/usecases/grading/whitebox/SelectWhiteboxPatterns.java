package graduation_project_be.application.usecases.grading.whitebox;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Case-insensitive regex detectors for text-safe SELECT white-box checks. All patterns run on SQL
 * already cleaned by {@link SqlTextPreprocessor} (comments / strings / bracket-ids neutralised).
 */
public final class SelectWhiteboxPatterns {

    private SelectWhiteboxPatterns() {
    }

    static final Pattern JOIN = ci("\\bJOIN\\b");
    static final Pattern LEFT_JOIN = ci("\\bLEFT\\s+(OUTER\\s+)?JOIN\\b");
    static final Pattern INNER_JOIN = ci("\\bINNER\\s+JOIN\\b");
    static final Pattern CROSS_JOIN = ci("\\bCROSS\\s+JOIN\\b");
    static final Pattern CTE = ci("\\bWITH\\s+[\\[\\]\\w]+\\s+AS\\s*\\(");
    static final Pattern SELECT_STAR = ci("\\bSELECT\\s+(DISTINCT\\s+)?(TOP\\s*\\(?\\s*\\d+\\s*\\)?\\s+)?([\\[\\]\\w]+\\.)?\\*");
    static final Pattern DISTINCT = ci("\\bSELECT\\s+DISTINCT\\b");
    static final Pattern GROUP_BY = ci("\\bGROUP\\s+BY\\b");
    static final Pattern HAVING = ci("\\bHAVING\\b");
    static final Pattern ORDER_BY = ci("\\bORDER\\s+BY\\b");
    static final Pattern WINDOW = ci("\\bOVER\\s*\\(");
    static final Pattern SET_OPERATOR = ci("\\b(UNION|INTERSECT|EXCEPT)\\b");
    static final Pattern AGGREGATE = ci("\\b(SUM|COUNT|AVG|MIN|MAX)\\s*\\(");

    static final List<String> DEFAULT_AGGREGATES = List.of("SUM", "COUNT", "AVG", "MIN", "MAX");
    static final List<String> DEFAULT_SET_OPERATORS = List.of("UNION", "INTERSECT", "EXCEPT");

    private static Pattern ci(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    static boolean find(String sql, Pattern pattern) {
        return sql != null && pattern.matcher(sql).find();
    }

    static int count(String sql, Pattern pattern) {
        if (sql == null) {
            return 0;
        }
        Matcher m = pattern.matcher(sql);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** A bare keyword such as TOP or NOLOCK, matched on word boundaries. */
    static boolean keywordPresent(String sql, String keyword) {
        return sql != null && Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b",
                Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    /** A function call such as FORMAT( or DATEDIFF(. */
    static boolean functionPresent(String sql, String function) {
        return sql != null && Pattern.compile("\\b" + Pattern.quote(function) + "\\s*\\(",
                Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }
}
