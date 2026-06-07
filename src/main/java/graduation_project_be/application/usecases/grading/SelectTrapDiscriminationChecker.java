package graduation_project_be.application.usecases.grading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that a trap dataset actually discriminates a known-wrong query from the model answer.
 *
 * <p>The teacher's AI-generated traps are meant to make a structurally-wrong query produce a
 * different result from {@code correctQuery}. This checker mutates the model answer with common
 * student mistakes (drop a JOIN condition, {@code LIKE}→{@code =}, {@code INNER}→{@code LEFT}) and
 * reports any mutation whose result on the trap data is identical to the model answer — that trap
 * cannot catch the mistake and should be strengthened before the exam is published. It only informs
 * the teacher at test-grade time; it never affects student grading.
 */
public class SelectTrapDiscriminationChecker {

    /** A known-wrong rewrite of the model answer plus a human label for the mistake it injects. */
    public record Mutation(String label, String mutatedSql) {
    }

    private static final Pattern INNER_JOIN = Pattern.compile("(?i)\\bINNER\\s+JOIN\\b");
    private static final Pattern BARE_JOIN = Pattern.compile(
            "(?i)(?<!LEFT )(?<!RIGHT )(?<!FULL )(?<!CROSS )(?<!OUTER )(?<!INNER )\\bJOIN\\b");
    private static final Pattern LIKE = Pattern.compile("(?i)\\bLIKE\\b");
    private static final Pattern ON_WITH_AND = Pattern.compile(
            "(?is)(\\bON\\b\\s+.+?)\\s+AND\\s+.+?"
                    + "(?=\\s+(?:INNER\\b|LEFT\\b|RIGHT\\b|FULL\\b|CROSS\\b|JOIN\\b|WHERE\\b|GROUP\\b|ORDER\\b|HAVING\\b)|$)");

    /** Builds the applicable known-wrong mutants of the model answer (empty when none apply). */
    public List<Mutation> mutate(String correctQuery) {
        List<Mutation> mutations = new ArrayList<>();
        if (correctQuery == null || correctQuery.isBlank()) {
            return mutations;
        }

        String innerToLeft = innerJoinToLeftJoin(correctQuery);
        if (innerToLeft != null) {
            mutations.add(new Mutation("đổi INNER JOIN thành LEFT JOIN", innerToLeft));
        }

        Matcher likeMatcher = LIKE.matcher(correctQuery);
        if (likeMatcher.find()) {
            mutations.add(new Mutation("đổi LIKE thành =", likeMatcher.replaceFirst("=")));
        }

        Matcher onMatcher = ON_WITH_AND.matcher(correctQuery);
        if (onMatcher.find()) {
            String dropped = correctQuery.substring(0, onMatcher.start())
                    + onMatcher.group(1)
                    + correctQuery.substring(onMatcher.end());
            if (!dropped.equalsIgnoreCase(correctQuery)) {
                mutations.add(new Mutation("bỏ bớt một điều kiện JOIN", dropped));
            }
        }
        return mutations;
    }

    private String innerJoinToLeftJoin(String sql) {
        Matcher inner = INNER_JOIN.matcher(sql);
        if (inner.find()) {
            return sql.substring(0, inner.start()) + "LEFT JOIN" + sql.substring(inner.end());
        }
        Matcher bare = BARE_JOIN.matcher(sql);
        if (bare.find()) {
            return sql.substring(0, bare.start()) + "LEFT JOIN" + sql.substring(bare.end());
        }
        return null;
    }

    /**
     * Returns true when two result sets are equal as multisets (row order ignored, cell values
     * compared as normalized strings) — i.e. the trap failed to tell the queries apart.
     */
    public boolean sameResult(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        return rowSignatures(a).equals(rowSignatures(b));
    }

    private List<String> rowSignatures(List<Map<String, Object>> rows) {
        List<String> signatures = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                if (row != null) {
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        sorted.put(entry.getKey(), entry.getValue() == null ? "∅" : String.valueOf(entry.getValue()));
                    }
                }
                signatures.add(sorted.toString());
            }
        }
        Collections.sort(signatures);
        return signatures;
    }
}
