package graduation_project_be.application.usecases;

import graduation_project_be.application.usecases.grading.whitebox.WhiteboxCatalog;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxCatalogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for WhiteboxCatalogUsecase: returns the white-box rule catalog for a question type.
 */
class WhiteboxCatalogUsecaseTest {

    private final WhiteboxCatalogUsecase usecase = new WhiteboxCatalogUsecase(new WhiteboxCatalog());

    @Test
    void execute_selectQuery_returns29Entries() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("SELECT_QUERY");

        assertEquals(29, entries.size(), "SELECT_QUERY should have 29 rules");
    }

    @Test
    void execute_nullQuestionType_defaultsToSelectQuery() {
        List<WhiteboxCatalogEntry> entries = usecase.execute(null);

        assertEquals(29, entries.size(), "Null question type should default to SELECT_QUERY");
    }

    @Test
    void execute_blankQuestionType_defaultsToSelectQuery() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("  ");

        assertEquals(29, entries.size(), "Blank question type should default to SELECT_QUERY");
    }

    @Test
    void execute_lowercaseQuestionType_normalized() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("select_query");

        assertEquals(29, entries.size(), "Lowercase question type should be normalized to uppercase");
    }

    @Test
    void execute_mixedCaseQuestionType_normalized() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("Select_Query");

        assertEquals(29, entries.size(), "Mixed case question type should be normalized to uppercase");
    }

    @Test
    void execute_unknownQuestionType_returnsEmpty() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("CREATE_TABLE");

        assertTrue(entries.isEmpty(), "Unknown question type should return empty list");
    }

    @Test
    void execute_insertDataQuestionType_returnsEmpty() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("INSERT_DATA");

        assertTrue(entries.isEmpty(), "INSERT_DATA question type is not supported in v1");
    }

    @Test
    void execute_storedProcedureQuestionType_returnsEmpty() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("STORED_PROCEDURE");

        assertTrue(entries.isEmpty(), "STORED_PROCEDURE question type is not supported in v1");
    }

    @Test
    void execute_selectQueryEntryHasRuleId() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("SELECT_QUERY");

        for (WhiteboxCatalogEntry entry : entries) {
            assertTrue(entry.ruleId() != null && !entry.ruleId().isBlank(),
                    "Every entry must have a ruleId");
        }
    }

    @Test
    void execute_selectQueryEntryHasLabel() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("SELECT_QUERY");

        for (WhiteboxCatalogEntry entry : entries) {
            assertTrue(entry.label() != null && !entry.label().isBlank(),
                    "Every entry must have a label");
        }
    }

    @Test
    void execute_selectQueryEntryHasType() {
        List<WhiteboxCatalogEntry> entries = usecase.execute("SELECT_QUERY");

        for (WhiteboxCatalogEntry entry : entries) {
            assertTrue(entry.type() != null, "Every entry must have a type");
        }
    }
}
