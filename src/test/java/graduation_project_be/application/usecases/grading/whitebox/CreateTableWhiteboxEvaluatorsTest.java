package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.infrastructure.services.JSqlParserSelectQueryStructureAnalyzer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateTableWhiteboxEvaluatorsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhiteboxCatalog catalog = new WhiteboxCatalog();
    private final WhiteboxEngine engine =
            new WhiteboxEngine(new JSqlParserSelectQueryStructureAnalyzer(), catalog);

    private record Case(String ruleId, String params, String failSql, String passSql) {
    }

    private static final String NAMED_TABLE = """
            CREATE TABLE [dbo].[Child] (
              [id] INT IDENTITY(1,1) NOT NULL,
              [parent_id] INT NOT NULL,
              [email] VARCHAR(100) NOT NULL DEFAULT '',
              [score] INT NOT NULL,
              CONSTRAINT [PK_Child] PRIMARY KEY ([id]),
              CONSTRAINT [FK_Child_Parent] FOREIGN KEY ([parent_id]) REFERENCES [dbo].[Parent]([id]),
              CONSTRAINT [UQ_Child_Email] UNIQUE ([email]),
              CONSTRAINT [CK_Child_Score] CHECK ([score] >= 0)
            );
            """;

    private static final List<Case> CASES = List.of(
            new Case("REQUIRED_PK", "{}",
                    "CREATE TABLE A (id INT); CREATE TABLE B (id INT PRIMARY KEY);",
                    "CREATE TABLE A (id INT, CONSTRAINT PK_A PRIMARY KEY(id));"
                            + " CREATE TABLE B (id INT, CONSTRAINT PK_B PRIMARY KEY(id));"),
            new Case("REQUIRED_CONSTRAINT_NAME", "{}",
                    "CREATE TABLE A (id INT PRIMARY KEY);",
                    "CREATE TABLE A (id INT, CONSTRAINT PK_A PRIMARY KEY(id));"),
            new Case("REQUIRED_FK", "{\"referenced_tables\":[\"Parent\"]}",
                    "CREATE TABLE Child (id INT, CONSTRAINT PK_C PRIMARY KEY(id));",
                    NAMED_TABLE),
            new Case("REQUIRED_NOT_NULL", "{\"columns\":[\"email\"]}",
                    "CREATE TABLE A (id INT, email VARCHAR(100), CONSTRAINT PK_A PRIMARY KEY(id));",
                    NAMED_TABLE),
            new Case("REQUIRED_DEFAULT_VALUE", "{\"columns\":[\"email\"]}",
                    "CREATE TABLE A (id INT, email VARCHAR(100), CONSTRAINT PK_A PRIMARY KEY(id));",
                    NAMED_TABLE),
            new Case("REQUIRED_CHECK_CONSTRAINT", "{\"columns\":[\"score\"]}",
                    "CREATE TABLE A (id INT, score INT, CONSTRAINT PK_A PRIMARY KEY(id));",
                    NAMED_TABLE),
            new Case("REQUIRED_UNIQUE_CONSTRAINT", "{\"columns\":[\"email\"]}",
                    "CREATE TABLE A (id INT, email VARCHAR(100), CONSTRAINT PK_A PRIMARY KEY(id));",
                    NAMED_TABLE),
            new Case("REQUIRED_IDENTITY", "{}",
                    "CREATE TABLE A (id INT, CONSTRAINT PK_A PRIMARY KEY(id));",
                    NAMED_TABLE),
            new Case("FORBIDDEN_IDENTITY", "{}",
                    NAMED_TABLE,
                    "CREATE TABLE A (id INT, CONSTRAINT PK_A PRIMARY KEY(id));"),
            new Case("FORBIDDEN_DEPRECATED_TYPE", "{}",
                    "CREATE TABLE A (id INT, note TEXT, CONSTRAINT PK_A PRIMARY KEY(id));",
                    "CREATE TABLE A (id INT, note NVARCHAR(MAX), CONSTRAINT PK_A PRIMARY KEY(id));"),
            new Case("FORBIDDEN_NOCHECK", "{}",
                    "ALTER TABLE A WITH NOCHECK ADD CONSTRAINT FK_A FOREIGN KEY(id) REFERENCES B(id);",
                    "ALTER TABLE A ADD CONSTRAINT FK_A FOREIGN KEY(id) REFERENCES B(id);"),
            new Case("FORBIDDEN_DROP_TABLE", "{}",
                    "DROP TABLE IF EXISTS A; CREATE TABLE A(id INT);",
                    "CREATE TABLE A(id INT);"),
            new Case("FORBIDDEN_SELECT_INTO", "{}",
                    "SELECT id INTO NewTable FROM OldTable;",
                    "CREATE TABLE NewTable(id INT);"));

    @Test
    void exposesExactlyThe13SpecifiedCreateTableRules() {
        assertEquals(13, catalog.entriesFor("CREATE_TABLE").size());
        assertEquals(13, CASES.size());
    }

    @Test
    void eachRuleFailsItsBadExampleAndPassesItsGoodExample() {
        for (Case c : CASES) {
            assertEquals(0, BigDecimal.ONE.compareTo(deduction(c.ruleId(), c.params(), c.failSql())),
                    c.ruleId() + " should fail");
            assertEquals(0, BigDecimal.ZERO.compareTo(deduction(c.ruleId(), c.params(), c.passSql())),
                    c.ruleId() + " should pass");
        }
    }

    @Test
    void forbiddenKeywordsInsideCommentsAndStringsDoNotFail() {
        assertEquals(0, BigDecimal.ZERO.compareTo(deduction(
                "FORBIDDEN_DROP_TABLE", "{}",
                "-- DROP TABLE A\nCREATE TABLE A(id INT, note VARCHAR(100) DEFAULT 'DROP TABLE B');")));
        assertEquals(0, BigDecimal.ZERO.compareTo(deduction(
                "FORBIDDEN_NOCHECK", "{}",
                "/* ALTER TABLE A WITH NOCHECK */ CREATE TABLE A(id INT);")));
        assertEquals(0, BigDecimal.ZERO.compareTo(deduction(
                "FORBIDDEN_SELECT_INTO", "{}",
                "CREATE TABLE A(id INT, note VARCHAR(100) DEFAULT 'SELECT id INTO B FROM A');")));
    }

    @Test
    void namedConstraintsSupportInlineAndTableLevelForms() {
        String sql = """
                CREATE TABLE A (
                  id INT CONSTRAINT PK_A PRIMARY KEY,
                  code INT CONSTRAINT UQ_A_Code UNIQUE,
                  score INT CONSTRAINT CK_A_Score CHECK (score > 0)
                );
                """;
        assertEquals(0, BigDecimal.ZERO.compareTo(
                deduction("REQUIRED_CONSTRAINT_NAME", "{}", sql)));
    }

    @Test
    void canonicalParamsSupportMultipleForeignKeysAndColumns() {
        String sql = """
                CREATE TABLE Child (
                  id INT NOT NULL,
                  parent_a INT NOT NULL,
                  parent_b INT NOT NULL,
                  CONSTRAINT PK_Child PRIMARY KEY (id),
                  CONSTRAINT FK_Child_A FOREIGN KEY (parent_a) REFERENCES ParentA(id),
                  CONSTRAINT FK_Child_B FOREIGN KEY (parent_b) REFERENCES ParentB(id)
                );
                """;
        assertEquals(0, BigDecimal.ZERO.compareTo(deduction(
                "REQUIRED_FK",
                "{\"referenced_table\":[\"ParentA\",\"ParentB\"]}",
                sql)));
        assertEquals(0, BigDecimal.ZERO.compareTo(deduction(
                "REQUIRED_NOT_NULL",
                "{\"column\":[\"parent_a\",\"parent_b\"]}",
                sql)));
    }

    private BigDecimal deduction(String ruleId, String paramsJson, String sql) {
        WhiteboxRule rule = new WhiteboxRule(ruleId, true, null, BigDecimal.ONE,
                WhiteboxPenaltyUnit.ABSOLUTE, WhiteboxSeverity.DEDUCTION, null, params(paramsJson));
        WhiteboxResult result = engine.evaluate("CREATE_TABLE", sql, List.of(rule),
                WhiteboxSettings.defaults(), BigDecimal.TEN, false);
        return result.cappedDeduction();
    }

    private JsonNode params(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
