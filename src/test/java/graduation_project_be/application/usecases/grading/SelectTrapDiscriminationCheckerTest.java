package graduation_project_be.application.usecases.grading;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure coverage for the trap-discrimination checker: mutant generation for the three starter
 * mistakes and the result-set comparison that decides whether a trap is too weak.
 */
class SelectTrapDiscriminationCheckerTest {

    private final SelectTrapDiscriminationChecker checker = new SelectTrapDiscriminationChecker();

    private String mutantFor(String sql, String labelFragment) {
        return checker.mutate(sql).stream()
                .filter(m -> m.label().contains(labelFragment))
                .map(SelectTrapDiscriminationChecker.Mutation::mutatedSql)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> row(String key, Object value) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(key, value);
        return r;
    }

    // --- mutation generation ---

    @Test
    void innerJoinMutatedToLeftJoin() {
        String m = mutantFor("SELECT * FROM a INNER JOIN b ON a.id = b.id", "INNER JOIN");
        assertTrue(m != null && m.toUpperCase().contains("LEFT JOIN"));
        assertFalse(m.toUpperCase().contains("INNER JOIN"));
    }

    @Test
    void bareJoinMutatedToLeftJoin() {
        String m = mutantFor("SELECT * FROM a JOIN b ON a.id = b.id", "INNER JOIN");
        assertTrue(m != null && m.toUpperCase().contains("LEFT JOIN"));
    }

    @Test
    void leftJoinNotReMutated() {
        // A model answer that already uses LEFT JOIN and nothing else must yield no INNER->LEFT mutant.
        assertEquals(null, mutantFor("SELECT * FROM a LEFT JOIN b ON a.id = b.id", "INNER JOIN"));
    }

    @Test
    void likeMutatedToEquals() {
        String m = mutantFor("SELECT * FROM a WHERE a.name LIKE 'Nguyen%'", "LIKE");
        assertTrue(m != null && m.contains("="));
        assertFalse(m.toUpperCase().contains("LIKE"));
    }

    @Test
    void dropOneJoinConditionRemovesConjunct() {
        String sql = "SELECT * FROM a JOIN b ON a.id = b.id AND a.cty = b.cty WHERE a.x > 0";
        String m = mutantFor(sql, "điều kiện JOIN");
        assertTrue(m != null && !m.equalsIgnoreCase(sql));
        assertFalse(m.toUpperCase().contains("A.CTY = B.CTY"));
        assertTrue(m.toUpperCase().contains("WHERE"));
    }

    @Test
    void flatQueryHasNoMutations() {
        assertTrue(checker.mutate("SELECT id FROM a").isEmpty());
        assertTrue(checker.mutate(null).isEmpty());
    }

    // --- discrimination decision (weak-trap detection) ---

    @Test
    void identicalResultsFlaggedAsNonDiscriminating() {
        List<Map<String, Object>> correct = List.of(row("id", 1), row("id", 2));
        List<Map<String, Object>> mutant = List.of(row("id", 2), row("id", 1)); // same multiset, reordered
        assertTrue(checker.sameResult(correct, mutant), "weak trap: equal result sets must be flagged");
    }

    @Test
    void differingResultsAreDiscriminating() {
        List<Map<String, Object>> correct = List.of(row("id", 1), row("id", 2));
        List<Map<String, Object>> mutant = List.of(row("id", 1));
        assertFalse(checker.sameResult(correct, mutant));
    }

    @Test
    void nullCellsCompareEqual() {
        List<Map<String, Object>> a = List.of(row("total", null));
        List<Map<String, Object>> b = List.of(row("total", null));
        assertTrue(checker.sameResult(a, b));
    }
}
