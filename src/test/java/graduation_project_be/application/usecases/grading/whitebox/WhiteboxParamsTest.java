package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for WhiteboxParams: extracts typed values from rule params JSON.
 */
class WhiteboxParamsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private WhiteboxRule rule(String paramsJson) throws Exception {
        JsonNode params = paramsJson == null ? null : mapper.readTree(paramsJson);
        return new WhiteboxRule("TEST_RULE", true, null, null, null, null, null, params);
    }

    @Test
    void intParam_number() throws Exception {
        WhiteboxRule rule = rule("""
                {"max": 10}
                """);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(10, result);
    }

    @Test
    void intParam_numericString() throws Exception {
        WhiteboxRule rule = rule("""
                {"max": "25"}
                """);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(25, result);
    }

    @Test
    void intParam_numericStringWithWhitespace() throws Exception {
        WhiteboxRule rule = rule("""
                {"max": "  42  "}
                """);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(42, result);
    }

    @Test
    void intParam_missing() throws Exception {
        WhiteboxRule rule = rule("""
                {"other": 5}
                """);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(99, result);
    }

    @Test
    void intParam_null() throws Exception {
        WhiteboxRule rule = rule("""
                {"max": null}
                """);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(99, result);
    }

    @Test
    void intParam_nullParamsObject() throws Exception {
        WhiteboxRule rule = rule(null);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(99, result);
    }

    @Test
    void intParam_nonNumericString() throws Exception {
        WhiteboxRule rule = rule("""
                {"max": "not a number"}
                """);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(99, result);
    }

    @Test
    void intParam_negative() throws Exception {
        WhiteboxRule rule = rule("""
                {"max": -5}
                """);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(-5, result);
    }

    @Test
    void intParam_zero() throws Exception {
        WhiteboxRule rule = rule("""
                {"max": 0}
                """);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(0, result);
    }

    @Test
    void stringList_jsonArray() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": ["SELECT", "FROM", "WHERE"]}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(3, result.size());
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_csvString() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": "SELECT,FROM,WHERE"}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(3, result.size());
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_csvWithWhitespace() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": " SELECT , FROM , WHERE "}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(3, result.size());
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_lowercase() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": "select,from,where"}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_mixedCase() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": "Select,FROM,WhErE"}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_deduplicates() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": "SELECT,FROM,SELECT,WHERE,FROM"}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(3, result.size());
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_dropsEmptyTokens() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": "SELECT,,FROM,,WHERE"}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(3, result.size());
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_dropsWhitespaceOnlyTokens() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": "SELECT,   ,FROM,  , WHERE"}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(3, result.size());
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_arrayWithMixedCase() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": ["select", "FROM", "Where"]}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_arrayWithEmptyStrings() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": ["SELECT", "", "FROM", "  ", "WHERE"]}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(3, result.size());
        assertEquals(List.of("SELECT", "FROM", "WHERE"), result);
    }

    @Test
    void stringList_missing() throws Exception {
        WhiteboxRule rule = rule("""
                {"other": ["something"]}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertTrue(result.isEmpty());
    }

    @Test
    void stringList_nullParamsObject() throws Exception {
        WhiteboxRule rule = rule(null);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertTrue(result.isEmpty());
    }

    @Test
    void stringList_null() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": null}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertTrue(result.isEmpty());
    }

    @Test
    void stringList_emptyArray() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": []}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertTrue(result.isEmpty());
    }

    @Test
    void stringList_emptyCsvString() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": ""}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertTrue(result.isEmpty());
    }

    @Test
    void stringList_csvOnlyCommas() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": ",,,"}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertTrue(result.isEmpty());
    }

    @Test
    void stringList_single() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": "SINGLE"}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(1, result.size());
        assertEquals("SINGLE", result.get(0));
    }

    @Test
    void stringList_preserveInsertionOrder() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": "Z,A,M,B"}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(List.of("Z", "A", "M", "B"), result);
    }

    @Test
    void stringList_dedupPreservesFirstOccurrence() throws Exception {
        WhiteboxRule rule = rule("""
                {"keywords": ["B", "A", "B", "C", "A"]}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(List.of("B", "A", "C"), result);
    }

    @Test
    void intParam_float_truncates() throws Exception {
        WhiteboxRule rule = rule("""
                {"max": 10.7}
                """);
        int result = WhiteboxParams.intParam(rule, "max", 99);
        assertEquals(10, result); // asInt() truncates
    }

    @Test
    void stringList_arrayWithNumericElements() throws Exception {
        // Numbers in array are converted to strings
        WhiteboxRule rule = rule("""
                {"keywords": [123, 456]}
                """);
        List<String> result = WhiteboxParams.stringList(rule, "keywords");
        assertEquals(2, result.size());
        assertEquals(List.of("123", "456"), result);
    }
}
