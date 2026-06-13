package graduation_project_be.application.usecases.grading.whitebox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog must expose only evaluator-backed SELECT rules (no placeholders), and non-SELECT
 * question types must expose nothing in v1.
 */
class WhiteboxCatalogTest {

    private static final Set<String> PARSER_REQUIRED = Set.of(
            "FORBIDDEN_SUBQUERY", "MAX_SUBQUERY_DEPTH", "FORBIDDEN_CORRELATED_SUBQUERY",
            "FORBIDDEN_OLD_JOIN_SYNTAX");

    private final WhiteboxCatalog catalog = new WhiteboxCatalog();

    @Test
    void exposes29SelectRules() {
        assertEquals(29, catalog.entriesFor("SELECT_QUERY").size());
    }

    @Test
    void everyExposedRuleIsEvaluatorBackedAndWellFormed() {
        List<WhiteboxCatalogEntry> entries = catalog.entriesFor("SELECT_QUERY");
        for (WhiteboxCatalogEntry e : entries) {
            assertNotNull(catalog.evaluator(e.ruleId()), e.ruleId() + " missing evaluator");
            assertNotNull(e.type(), e.ruleId() + " missing type");
            assertNotNull(e.label(), e.ruleId() + " missing label");
            assertEquals(WhiteboxSeverity.WARNING_ONLY, e.defaultSeverity(),
                    e.ruleId() + " default severity must be WARNING_ONLY");
            assertTrue(e.questionTypes().contains("SELECT_QUERY"));
        }
    }

    @Test
    void parserRequiredFlagsMatchTheStructuralRules() {
        for (WhiteboxCatalogEntry e : catalog.entriesFor("SELECT_QUERY")) {
            assertEquals(PARSER_REQUIRED.contains(e.ruleId()), e.parserRequired(),
                    e.ruleId() + " parserRequired flag mismatch");
        }
    }

    @Test
    void nonSelectQuestionTypesExposeNothingInV1() {
        assertTrue(catalog.entriesFor("CREATE_TABLE").isEmpty());
        assertTrue(catalog.entriesFor("INSERT_DATA").isEmpty());
        assertTrue(catalog.entriesFor("STORED_PROCEDURE").isEmpty());
    }

    @Test
    void unknownRuleIsNotSupported() {
        assertFalse(catalog.supports("NOT_A_RULE"));
        assertTrue(catalog.supports("forbidden_subquery")); // case-insensitive
    }
}
