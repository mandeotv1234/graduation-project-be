package graduation_project_be.application.usecases.grading.whitebox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Case-insensitive regex detectors for TRIGGER white-box checks. All patterns run on SQL already
 * cleaned by {@link SqlTextPreprocessor}. Shared detectors (SET NOCOUNT ON, CURSOR, PRINT, ROLLBACK)
 * are reused from {@link RoutineWhiteboxPatterns} by the rule set, not redefined here.
 */
final class TriggerWhiteboxPatterns {

    private TriggerWhiteboxPatterns() {
    }

    // ---- Pseudo-tables ----
    static final Pattern INSERTED_TABLE = ci("\\bINSERTED\\b");
    static final Pattern DELETED_TABLE = ci("\\bDELETED\\b");

    // ---- Timing / event header ----
    static final Pattern AFTER_OR_FOR_EVENT = ci("\\b(AFTER|FOR)\\s+(INSERT|UPDATE|DELETE)\\b");
    static final Pattern INSTEAD_OF = ci("\\bINSTEAD\\s+OF\\b");

    // ---- Column-change check ----
    static final Pattern UPDATE_FN_CHECK = ci("\\bUPDATE\\s*\\(\\s*\\w+\\s*\\)|\\bCOLUMNS_UPDATED\\s*\\(");

    // ---- Multi-row safety: scalar assignment pulled from INSERTED/DELETED ----
    static final Pattern SCALAR_FROM_PSEUDO =
            ci("\\bSELECT\\s+@\\w+\\s*=[\\s\\S]{0,160}?\\bFROM\\s+(INSERTED|DELETED)\\b");
    static final Pattern AGGREGATE_OR_TOP = ci("\\b(MAX|MIN|SUM|COUNT|AVG|TOP)\\b");

    // ---- Result set returned to client (SELECT that is neither an assignment nor a SELECT ... INTO) ----
    static final Pattern RESULTSET_SELECT = ci(
            "(?<![\\w(.])\\bSELECT\\s+"
                    + "(?!(?:TOP\\s+\\d+\\s+|DISTINCT\\s+)*@\\w+\\s*=)" // not a variable assignment
                    + "(?![^;]*\\bINTO\\b)"                              // not SELECT ... INTO
                    + "[^;]*?\\bFROM\\b");
    private static final Pattern INSERT_BEFORE = ci("\\bINSERT\\b");

    private static Pattern ci(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /**
     * True when the trigger assigns a scalar variable from INSERTED/DELETED without an aggregate/TOP,
     * i.e. silently assumes a single affected row and breaks on multi-row DML.
     */
    static boolean multiRowUnsafe(String sql) {
        if (sql == null) {
            return false;
        }
        Matcher m = SCALAR_FROM_PSEUDO.matcher(sql);
        while (m.find()) {
            if (!AGGREGATE_OR_TOP.matcher(m.group()).find()) {
                return true;
            }
        }
        return false;
    }

    /** Matched evidence for {@link #multiRowUnsafe}, or null. */
    static String multiRowUnsafeEvidence(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher m = SCALAR_FROM_PSEUDO.matcher(sql);
        while (m.find()) {
            if (!AGGREGATE_OR_TOP.matcher(m.group()).find()) {
                String hit = m.group().trim().replaceAll("\\s+", " ");
                return hit.length() > 80 ? hit.substring(0, 80) + "…" : hit;
            }
        }
        return null;
    }

    /**
     * True when the trigger is declared with the given name via
     * {@code CREATE [OR ALTER] TRIGGER [schema.]<name>}.
     */
    static boolean hasTriggerName(String sql, String name) {
        if (sql == null || name == null || name.isBlank()) {
            return false;
        }
        String n = Pattern.quote(name.trim());
        Pattern p = Pattern.compile(
                "\\bCREATE\\s+(?:OR\\s+ALTER\\s+)?TRIGGER\\s+(?:\\[?\\w+\\]?\\s*\\.\\s*)?\\[?" + n + "\\]?\\b",
                Pattern.CASE_INSENSITIVE);
        return p.matcher(sql).find();
    }

    /** True when the trigger is bound to the given table via {@code ON [dbo.]<table>}. */
    static boolean onTable(String sql, String table) {
        if (sql == null || table == null || table.isBlank()) {
            return false;
        }
        String t = Pattern.quote(table.trim());
        Pattern p = Pattern.compile(
                "\\bON\\s+(?:\\[?dbo\\]?\\s*\\.\\s*)?\\[?" + t + "\\]?\\b",
                Pattern.CASE_INSENSITIVE);
        return p.matcher(sql).find();
    }

    /** Events from {@code params.events} not present in the trigger definition. */
    static List<String> missingEvents(String sql, List<String> requiredEvents) {
        List<String> missing = new ArrayList<>();
        if (sql == null || requiredEvents == null) {
            return missing;
        }
        for (String event : requiredEvents) {
            if (event == null || event.isBlank()) {
                continue;
            }
            Pattern p = Pattern.compile("\\b" + Pattern.quote(event.trim()) + "\\b",
                    Pattern.CASE_INSENSITIVE);
            if (!p.matcher(sql).find()) {
                missing.add(event.trim().toUpperCase(Locale.ROOT));
            }
        }
        return missing;
    }

    /**
     * True when a SELECT returns a result set to the client (not an assignment, not SELECT ... INTO,
     * not the SELECT part of an INSERT ... SELECT). Heuristic; defaults to WARNING_ONLY in the catalog.
     */
    static boolean returnsResultSet(String sql) {
        if (sql == null) {
            return false;
        }
        Matcher m = RESULTSET_SELECT.matcher(sql);
        while (m.find()) {
            String before = sql.substring(Math.max(0, m.start() - 40), m.start());
            if (INSERT_BEFORE.matcher(before).find()) {
                continue; // INSERT ... SELECT — populates a table, does not return rows
            }
            return true;
        }
        return false;
    }
}
