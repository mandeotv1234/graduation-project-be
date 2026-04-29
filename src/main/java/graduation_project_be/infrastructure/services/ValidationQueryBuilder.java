package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.TestCase;
import graduation_project_be.domain.models.enums.VerificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Builds the executable SQL for a {@link TestCase} based on its
 * {@link VerificationType}. The output is what the engine actually runs against
 * a target schema (teacher's during expected derivation, student's during
 * grading). All schema placeholders are resolved here.
 *
 * <p>Why a builder: the AI prompt asks Gemini to provide an "invocation_query"
 * and "validation_query" template — but those templates may use placeholders
 * like {SCHEMA} or rely on the engine to wire input_parameters. Centralizing
 * this avoids each call site reinventing the substitution logic.
 *
 * <p>Returned object encapsulates two distinct phases:
 *  <ul>
 *    <li>{@link Built#setupSql} — runs first, prepares test data
 *    <li>{@link Built#invocationSql} — runs the routine (may be null for pure
 *        Function RETURN_VALUE/RESULT_SET cases where validationSql already calls it)
 *    <li>{@link Built#validationSql} — reads the actual outcome
 *  </ul>
 *
 * <p>Engine pattern (caller's responsibility):
 * <pre>
 *   BEGIN TRAN sp_X
 *     run setupSql (if any)
 *     run invocationSql (if any)  -- side effect happens here
 *     actual = run validationSql  -- read outcome
 *     compare actual vs tc.expectedValue
 *   ROLLBACK TRAN sp_X
 * </pre>
 */
@Slf4j
@Component
public class ValidationQueryBuilder {

    private static final String SCHEMA_PLACEHOLDER = "{SCHEMA}";
    private static final String TEACHER_SCHEMA_PLACEHOLDER = "{TEACHER_SCHEMA}";

    private final ObjectMapper objectMapper;

    public ValidationQueryBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param testCase           Source TC. validationQuery may already be a literal
     *                           SQL or a template with {SCHEMA} placeholders.
     * @param targetSchemaName   The MSSQL schema where the routine lives (student
     *                           schema during grading, teacher schema during
     *                           expected derivation).
     * @param teacherSchemaName  Used to resolve {TEACHER_SCHEMA} placeholder when
     *                           test cases compare across both schemas. May be
     *                           null when irrelevant.
     */
    public Built build(TestCase testCase, String targetSchemaName, String teacherSchemaName) {
        VerificationType type = testCase.getVerificationType() != null
                ? testCase.getVerificationType()
                : VerificationType.RETURN_VALUE;

        String setup = resolvePlaceholders(testCase.getSetupScript(), targetSchemaName, teacherSchemaName);
        String invocation = resolvePlaceholders(testCase.getInvocationQuery(), targetSchemaName, teacherSchemaName);
        String validation = resolvePlaceholders(testCase.getValidationQuery(), targetSchemaName, teacherSchemaName);

        // Normalize: trim and treat blank as null so callers can `if (b.invocationSql != null)`.
        setup = blankToNull(setup);
        invocation = blankToNull(invocation);
        validation = blankToNull(validation);

        // For Function (RETURN_VALUE / RESULT_SET) the validation_query already
        // calls the function (e.g. SELECT dbo.fn(...)). Invocation is implicit.
        // For SP/Trigger SIDE_EFFECT/PRINT_OUTPUT/OUT_PARAMETER, invocation is the
        // EXEC or DML statement and validation reads the resulting state.
        return new Built(setup, invocation, validation, type);
    }

    /**
     * Builds an EXEC / SELECT-from-function string from a routine name and JSON
     * input_parameters object. Use this when AI did NOT supply an invocation_query
     * and the engine has to construct one from input_parameters alone.
     *
     * <p>Example: routineName = "fn_TotalByCustomer", inputJson = {"customerId": 1}
     * Returns: "SELECT [{SCHEMA}].[fn_TotalByCustomer](1)"
     *
     * <p>Currently used for FUNCTION RETURN_VALUE only; for SP / Trigger the AI
     * is expected to supply invocation_query directly (templates vary too much).
     */
    public String buildScalarFunctionCall(String schemaName, String routineName, String inputParametersJson) {
        String args = renderInputAsCommaSeparatedValues(inputParametersJson);
        return String.format("SELECT [%s].[%s](%s)", schemaName, routineName, args);
    }

    private String renderInputAsCommaSeparatedValues(String inputParametersJson) {
        if (inputParametersJson == null || inputParametersJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(inputParametersJson);
            if (!node.isObject()) {
                log.warn("input_parameters is not a JSON object: {}", inputParametersJson);
                return "";
            }
            List<String> values = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                values.add(jsonValueToSqlLiteral(it.next().getValue()));
            }
            return String.join(", ", values);
        } catch (Exception e) {
            log.warn("Failed to parse input_parameters JSON [{}]: {}", inputParametersJson, e.getMessage());
            return "";
        }
    }

    private String jsonValueToSqlLiteral(JsonNode node) {
        if (node == null || node.isNull()) {
            return "NULL";
        }
        if (node.isTextual()) {
            // Escape single quotes for T-SQL string literal.
            return "N'" + node.asText().replace("'", "''") + "'";
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? "1" : "0";
        }
        if (node.isNumber()) {
            return node.numberValue().toString();
        }
        // Fallback: stringify as text literal.
        return "N'" + node.toString().replace("'", "''") + "'";
    }

    private String resolvePlaceholders(String sql, String targetSchema, String teacherSchema) {
        if (sql == null) {
            return null;
        }
        String result = sql;
        if (targetSchema != null) {
            result = result.replace(SCHEMA_PLACEHOLDER, targetSchema);
        }
        if (teacherSchema != null) {
            result = result.replace(TEACHER_SCHEMA_PLACEHOLDER, teacherSchema);
        }
        return result;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Result of building all SQL pieces for a test case. */
    public record Built(String setupSql, String invocationSql, String validationSql, VerificationType type) {
    }
}
