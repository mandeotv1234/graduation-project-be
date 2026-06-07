package graduation_project_be.application.usecases.grading;

import graduation_project_be.infrastructure.services.JSqlParserSelectQueryStructureAnalyzer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit coverage for the JSQLParser-backed structural analyzer: one asserting case per
 * {@link QueryStructureFacts} field plus T-SQL dialect edges and the never-throw parse-fail
 * contract. No database, no Spring context.
 */
class SelectQueryStructureAnalyzerTest {

    private final JSqlParserSelectQueryStructureAnalyzer analyzer = new JSqlParserSelectQueryStructureAnalyzer();

    private QueryStructureFacts analyze(String sql) {
        return analyzer.analyze(sql);
    }

    // --- joinCount / fromTableCount ---

    @Test
    void explicitJoinCounted() {
        QueryStructureFacts f = analyze("SELECT * FROM a JOIN b ON a.id = b.id");
        assertTrue(f.parseOk());
        assertEquals(1, f.joinCount());
        assertEquals(2, f.fromTableCount());
    }

    @Test
    void commaJoinNotCountedAsJoinButRaisesFromTableCount() {
        QueryStructureFacts f = analyze("SELECT * FROM a, b WHERE a.id = b.id");
        assertTrue(f.parseOk());
        assertEquals(0, f.joinCount());
        assertEquals(2, f.fromTableCount());
    }

    @Test
    void singleTableHasNoJoins() {
        QueryStructureFacts f = analyze("SELECT id FROM a");
        assertEquals(0, f.joinCount());
        assertEquals(1, f.fromTableCount());
    }

    // --- subqueries by clause ---

    @Test
    void subqueryInSelectDetected() {
        QueryStructureFacts f = analyze("SELECT (SELECT MAX(x) FROM b) AS m FROM a");
        assertTrue(f.hasSubqueryInSelect());
        assertFalse(f.hasSubqueryInFrom());
        assertFalse(f.hasSubqueryInWhere());
    }

    @Test
    void subqueryInFromDetected() {
        QueryStructureFacts f = analyze("SELECT t.x FROM (SELECT x FROM b) t");
        assertTrue(f.hasSubqueryInFrom());
        assertFalse(f.hasSubqueryInSelect());
    }

    @Test
    void subqueryInWhereDetected() {
        QueryStructureFacts f = analyze("SELECT * FROM a WHERE a.id IN (SELECT id FROM b)");
        assertTrue(f.hasSubqueryInWhere());
        assertFalse(f.hasSubqueryInFrom());
    }

    // --- CTE / GROUP BY / DISTINCT / ORDER BY ---

    @Test
    void cteDetected() {
        QueryStructureFacts f = analyze("WITH c AS (SELECT id FROM a) SELECT * FROM c");
        assertTrue(f.hasCte());
    }

    @Test
    void groupByDetected() {
        QueryStructureFacts f = analyze("SELECT dept, COUNT(*) FROM emp GROUP BY dept");
        assertTrue(f.hasGroupBy());
    }

    @Test
    void distinctDetected() {
        QueryStructureFacts f = analyze("SELECT DISTINCT dept FROM emp");
        assertTrue(f.hasDistinct());
    }

    @Test
    void orderByDetected() {
        QueryStructureFacts f = analyze("SELECT id FROM a ORDER BY id");
        assertTrue(f.hasOrderBy());
    }

    // --- aggregates ---

    @Test
    void aggregateFunctionsCollectedUpperCased() {
        QueryStructureFacts f = analyze("SELECT count(id), SUM(salary) FROM emp");
        assertTrue(f.aggregateFns().contains("COUNT"));
        assertTrue(f.aggregateFns().contains("SUM"));
        assertFalse(f.aggregateFns().contains("AVG"));
    }

    @Test
    void noAggregateWhenAbsent() {
        QueryStructureFacts f = analyze("SELECT id, name FROM emp");
        assertTrue(f.aggregateFns().isEmpty());
    }

    // --- nesting depth ---

    @Test
    void nestingDepthZeroForFlatQuery() {
        QueryStructureFacts f = analyze("SELECT id FROM a");
        assertEquals(0, f.maxNestingDepth());
    }

    @Test
    void nestingDepthOneForSingleSubquery() {
        QueryStructureFacts f = analyze("SELECT * FROM a WHERE id IN (SELECT id FROM b)");
        assertEquals(1, f.maxNestingDepth());
    }

    @Test
    void nestingDepthTwoForDoublyNestedSubquery() {
        QueryStructureFacts f = analyze(
                "SELECT * FROM a WHERE id IN (SELECT id FROM b WHERE y IN (SELECT y FROM c))");
        assertEquals(2, f.maxNestingDepth());
    }

    // --- literal in WHERE ---

    @Test
    void literalInWhereDetected() {
        QueryStructureFacts f = analyze("SELECT * FROM a WHERE a.age = 18");
        assertTrue(f.hasLiteralInWhere());
    }

    @Test
    void noLiteralWhenWhereComparesColumns() {
        QueryStructureFacts f = analyze("SELECT * FROM a JOIN b ON a.id = b.id WHERE a.x = b.y");
        assertFalse(f.hasLiteralInWhere());
    }

    // --- T-SQL dialect edges ---

    @Test
    void squareBracketIdentifiersParse() {
        QueryStructureFacts f = analyze("SELECT [id], [name] FROM [dbo].[Users]");
        assertTrue(f.parseOk());
        assertEquals(1, f.fromTableCount());
    }

    @Test
    void tsqlTopClauseParses() {
        QueryStructureFacts f = analyze("SELECT TOP 5 id FROM a ORDER BY id");
        assertTrue(f.parseOk());
        assertTrue(f.hasOrderBy());
    }

    @Test
    void nPrefixedUnicodeStringLiteralParsesAndCountsAsLiteral() {
        QueryStructureFacts f = analyze("SELECT * FROM a WHERE a.name = N'Nguyen'");
        assertTrue(f.parseOk());
        assertTrue(f.hasLiteralInWhere());
    }

    // --- parse-fail contract: never throws, parseOk=false ---

    @Test
    void syntaxErrorReturnsParseFailed() {
        QueryStructureFacts f = analyze("SELCT FRM where");
        assertFalse(f.parseOk());
    }

    @Test
    void nonSelectStatementReturnsParseFailed() {
        QueryStructureFacts f = analyze("UPDATE a SET x = 1");
        assertFalse(f.parseOk());
    }

    @Test
    void nullAndBlankReturnParseFailed() {
        assertFalse(analyze(null).parseOk());
        assertFalse(analyze("   ").parseOk());
    }
}
