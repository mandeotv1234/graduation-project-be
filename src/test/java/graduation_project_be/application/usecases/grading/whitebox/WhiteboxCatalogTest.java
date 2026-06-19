package graduation_project_be.application.usecases.grading.whitebox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog must expose only evaluator-backed rules (no placeholders).
 */
class WhiteboxCatalogTest {

    private static final Set<String> PARSER_REQUIRED = Set.of(
            "FORBIDDEN_SUBQUERY", "MAX_SUBQUERY_DEPTH", "FORBIDDEN_CORRELATED_SUBQUERY",
            "FORBIDDEN_OLD_JOIN_SYNTAX", "FORBIDDEN_SUBQUERY_IN_SELECT",
            "FORBIDDEN_SUBQUERY_IN_FROM", "FORBIDDEN_SUBQUERY_IN_WHERE",
            "FORBIDDEN_SUBQUERY_IN_HAVING");

    private final WhiteboxCatalog catalog = new WhiteboxCatalog();

    @Test
    void exposes33SelectRules() {
        assertEquals(33, catalog.entriesFor("SELECT_QUERY").size());
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
    void everyExposedRuleHasFeaturePolicyAuthoringMetadata() {
        List<WhiteboxCatalogEntry> entries = catalog.entriesFor("SELECT_QUERY");
        for (WhiteboxCatalogEntry e : entries) {
            assertNotNull(e.featureId(), e.ruleId() + " missing featureId");
            assertFalse(e.featureId().isBlank(), e.ruleId() + " blank featureId");
            assertNotNull(e.featureLabel(), e.ruleId() + " missing featureLabel");
            assertFalse(e.featureLabel().isBlank(), e.ruleId() + " blank featureLabel");
            assertNotNull(e.featureKind(), e.ruleId() + " missing featureKind");
            assertNotNull(e.policy(), e.ruleId() + " missing policy");
            assertNotNull(e.policyLabel(), e.ruleId() + " missing policyLabel");
            assertFalse(e.policyLabel().isBlank(), e.ruleId() + " blank policyLabel");
            assertNotNull(e.conflictsWith(), e.ruleId() + " missing conflictsWith");
        }
    }

    @Test
    void numericRulesAreTheOnlyOnesWithNumberParams() {
        // featureKind=NUMERIC must carry a NUMBER param (the threshold); BOOLEAN rules carry none.
        for (WhiteboxCatalogEntry e : catalog.entriesFor("SELECT_QUERY")) {
            boolean hasNumberParam = e.params().stream()
                    .anyMatch(p -> WhiteboxParamSpec.TYPE_NUMBER.equals(p.type()));
            if (e.featureKind() == WhiteboxFeatureKind.NUMERIC) {
                assertTrue(hasNumberParam, e.ruleId() + " NUMERIC rule must expose a NUMBER param");
            } else {
                assertFalse(hasNumberParam, e.ruleId() + " non-NUMERIC rule must not expose a NUMBER param");
            }
        }
    }

    @Test
    void conflictMetadataReferencesExposedRulesAndIsSymmetric() {
        List<WhiteboxCatalogEntry> entries = catalog.entriesFor("SELECT_QUERY");
        Set<String> ids = entries.stream().map(WhiteboxCatalogEntry::ruleId).collect(java.util.stream.Collectors.toSet());
        for (WhiteboxCatalogEntry e : entries) {
            for (String other : e.conflictsWith()) {
                assertTrue(ids.contains(other),
                        e.ruleId() + " conflictsWith unknown rule " + other);
                assertTrue(catalog.entry(other).conflictsWith().contains(e.ruleId()),
                        "conflict must be symmetric: " + other + " should list " + e.ruleId());
            }
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
        assertEquals(13, catalog.entriesFor("CREATE_TABLE").size());
        assertTrue(catalog.entriesFor("INSERT_DATA").isEmpty());
    }

    @Test
    void unknownRuleIsNotSupported() {
        assertFalse(catalog.supports("NOT_A_RULE"));
        assertTrue(catalog.supports("forbidden_subquery")); // case-insensitive
    }
}
