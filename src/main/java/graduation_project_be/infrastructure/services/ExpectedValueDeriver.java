package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.SqlExecutionResult;
import graduation_project_be.domain.models.TestCase;
import graduation_project_be.domain.models.enums.VerificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Derives the {@link TestCase#getExpectedValue() expectedValue} for each test
 * case by running it against a sandbox schema that has the teacher's
 * correctQuery applied.
 *
 * <p>This is the heart of "AI never decides what's correct". The AI generates
 * a scenario (setup / invocation / validation) but we never trust whatever
 * "expected" it might have written — instead we run the teacher's reference
 * answer and capture whatever it produces. By construction, that capture is
 * the correct answer for this test case.
 *
 * <p>Workflow per call:
 * <ol>
 *   <li>Create a temporary schema {@code validate_<questionId>_<timestamp>}
 *   <li>Load DDL spec into it
 *   <li>Apply teacher's correctQuery (CREATE FN/SP/TRIGGER)
 *   <li>For each TC: run setup → invocation → validation, capture result, save
 *       as expectedValue. Each TC is wrapped in BEGIN/ROLLBACK so state is clean
 *       for the next one.
 *   <li>Drop the sandbox schema
 * </ol>
 *
 * <p>Failure modes the caller cares about:
 *  <ul>
 *    <li>{@link DerivationException} thrown if the teacher's correctQuery itself
 *        cannot be applied — the question is broken and should not be saved.
 *    <li>Per-TC failures are recorded inside {@link DerivationResult#testCaseErrors}
 *        and the call returns normally. The caller decides whether to fail-fast
 *        or accept partial results.
 *  </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpectedValueDeriver {

    private final ExamSchemaService examSchemaService;
    private final ValidationQueryBuilder validationQueryBuilder;

    /**
     * @param questionId   For sandbox naming + log context.
     * @param ddlScript    DDL spec (CREATE TABLE statements). May be null.
     * @param correctQuery Teacher's reference answer (CREATE FN/SP/TRIGGER).
     * @param testCases    Mutable list. expectedValue is set in-place on each
     *                     successfully-derived TC.
     * @return Per-TC errors (keyed by orderIndex) for any TC that failed to
     *         derive. Empty map = full success.
     */
    public DerivationResult derive(Long questionId, String ddlScript,
                                   String correctQuery, List<TestCase> testCases) {
        String sandboxSchema = "validate_q" + questionId + "_" + System.currentTimeMillis();
        Map<Integer, String> errors = new java.util.HashMap<>();

        try {
            // 1. Create sandbox + load DDL
            examSchemaService.resetSchema(sandboxSchema, false);
            if (ddlScript != null && !ddlScript.isBlank()) {
                examSchemaService.loadTemplateIntoSchema(sandboxSchema, ddlScript, null);
            }

            // 2. Apply teacher's correctQuery — fail-fast if this throws
            try {
                executeSqlScriptBatches(sandboxSchema, correctQuery);
            } catch (Exception e) {
                throw new DerivationException(
                        "Đáp án mẫu (correctQuery) không chạy được trên schema mẫu: " + e.getMessage(), e);
            }

            // 3. Per TC: setup + invocation + validation → capture
            for (TestCase tc : testCases) {
                int idx = tc.getOrderIndex() != null ? tc.getOrderIndex() : 0;
                try {
                    String captured = deriveOneTestCase(sandboxSchema, tc);
                    tc.setExpectedValue(captured);
                    log.info("[ExpectedValueDeriver] Q{} TC{} derived expected = {}",
                            questionId, idx, truncate(captured, 200));
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    errors.put(idx, msg);
                    log.warn("[ExpectedValueDeriver] Q{} TC{} derivation FAILED: {}",
                            questionId, idx, msg);
                }
            }
        } finally {
            try {
                examSchemaService.dropSchema(sandboxSchema);
            } catch (Exception e) {
                log.warn("[ExpectedValueDeriver] Failed to drop sandbox [{}]: {}",
                        sandboxSchema, e.getMessage());
            }
        }

        return new DerivationResult(errors);
    }

    private void executeSqlScriptBatches(String schemaName, String sqlScript) {
        if (sqlScript == null || sqlScript.isBlank()) {
            return;
        }

        String normalized = sqlScript
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();

        for (String goBatch : normalized.split("(?im)^\\s*GO\\s*;?\\s*$")) {
            for (String batch : splitBatchBeforeCreateRoutine(goBatch)) {
                String executable = batch.trim();
                if (!executable.isBlank()) {
                    examSchemaService.executeSql(schemaName, normalizeDboReferences(executable, schemaName));
                }
            }
        }
    }

    private String normalizeDboReferences(String sql, String schemaName) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        return sql.replaceAll("(?i)\\bdbo\\s*\\.", "[" + schemaName + "].");
    }

    private List<String> splitBatchBeforeCreateRoutine(String batch) {
        if (batch == null || batch.isBlank()) {
            return List.of();
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)\\bCREATE\\s+(?:OR\\s+ALTER\\s+)?(?:PROCEDURE|PROC|FUNCTION|TRIGGER)\\b")
                .matcher(batch);
        if (!matcher.find()) {
            return List.of(batch);
        }

        String prefix = batch.substring(0, matcher.start()).trim();
        String routine = batch.substring(matcher.start()).trim();
        if (prefix.isBlank()) {
            return List.of(routine);
        }
        return List.of(prefix, routine);
    }

    /**
     * Runs a single TC's setup → invocation → validation cycle on the sandbox
     * as ONE batch SQL so that BEGIN TRAN / ROLLBACK actually keep TC state
     * isolated.
     *
     * <p>Critical detail: jdbcTemplate.execute opens a new connection per call,
     * so we CANNOT split setup/invocation/validation into separate
     * executeAdminSql calls — the BEGIN TRAN would be on a connection that's
     * immediately released. Instead the whole TC runs as one batch on one
     * connection.
     */
    /**
     * Marker column emitted right before validation_query so that any rows
     * produced by setup_script or invocation_query (e.g. SP body with embedded
     * SELECT) can be filtered out before serialization. MUST match the constant
     * used by GradeExamUsecase so that derived and graded values are produced
     * by the same logic.
     */
    private static final String VALIDATION_MARKER_COLUMN = "__VALIDATION_MARKER__";

    private String deriveOneTestCase(String sandboxSchema, TestCase tc) {
        ValidationQueryBuilder.Built built = validationQueryBuilder.build(tc, sandboxSchema, sandboxSchema);

        StringBuilder batch = new StringBuilder();
        batch.append("BEGIN TRY\n");
        batch.append("  BEGIN TRANSACTION;\n");
        if (built.setupSql() != null && !built.setupSql().isBlank()) {
            batch.append("  ").append(built.setupSql()).append(";\n");
        }
        if (built.invocationSql() != null && !built.invocationSql().isBlank()) {
            batch.append("  ").append(built.invocationSql()).append(";\n");
        }
        if (built.validationSql() != null && !built.validationSql().isBlank()) {
            batch.append("  SELECT NULL AS ").append(VALIDATION_MARKER_COLUMN).append(";\n");
            batch.append("  ").append(built.validationSql()).append(";\n");
        }
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append("END TRY\n");
        batch.append("BEGIN CATCH\n");
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append("  THROW;\n");
        batch.append("END CATCH;");

        // Run as the sandbox's schema user, not as admin. The sandbox holds the
        // teacher's correctQuery — running as the schema user mirrors how
        // student grading executes (same EXECUTE AS USER context), so derived
        // expected_value reflects the same permission scope as the actual run.
        // Also gets query timeout, preventing teacher-side infinite loops from
        // blocking question creation.
        SqlExecutionResult execResult = examSchemaService.executeSqlBatchAsSchemaUser(sandboxSchema, batch.toString());

        // Capture per verification_type. MUST match the format used by
        // GradeExamUsecase.serializeResultForCompare so EXACT compare works.
        if (built.type() == VerificationType.PRINT_OUTPUT) {
            List<String> prints = execResult != null && execResult.getPrintMessages() != null
                    ? execResult.getPrintMessages()
                    : new ArrayList<>();
            return String.join("\n", prints).trim();
        }
        return serializeResult(dropRowsBeforeValidationMarker(execResult), built.type());
    }

    /** See note on the engine-side equivalent in GradeExamUsecase. */
    private SqlExecutionResult dropRowsBeforeValidationMarker(SqlExecutionResult execResult) {
        if (execResult == null || execResult.getResultSet() == null) {
            return execResult;
        }
        List<Map<String, Object>> rows = execResult.getResultSet();
        int markerIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).containsKey(VALIDATION_MARKER_COLUMN)) {
                markerIdx = i;
                break;
            }
        }
        if (markerIdx < 0) {
            return execResult;
        }
        List<Map<String, Object>> filtered = new ArrayList<>(rows.subList(markerIdx + 1, rows.size()));
        return SqlExecutionResult.builder()
                .resultSet(filtered)
                .rowCount(filtered.size())
                .statusMessage(execResult.getStatusMessage())
                .printMessages(execResult.getPrintMessages())
                .build();
    }

    /**
     * Single canonical string format for a result, matched against during grading
     * (see gradeByTestCases). Engine and Deriver MUST agree on this format.
     */
    private String serializeResult(SqlExecutionResult result, VerificationType type) {
        if (result == null || result.getResultSet() == null || result.getResultSet().isEmpty()) {
            return "";
        }
        List<Map<String, Object>> rows = result.getResultSet();

        // Single scalar (1x1) — most common for RETURN_VALUE / OUT_PARAMETER /
        // SIDE_EFFECT (where validation_query is "SELECT COUNT(*)").
        if (rows.size() == 1 && rows.get(0).size() == 1) {
            Object v = rows.get(0).values().iterator().next();
            return v == null ? "null" : v.toString().trim();
        }

        // Multi-row / multi-col — concatenate cells with "|" between cells and
        // "\n" between rows. Sort row strings for order-insensitive compare
        // (the grader applies the same sort before equality check).
        List<String> rowStrs = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object v : row.values()) {
                if (!first) sb.append("|");
                sb.append(v == null ? "null" : v.toString().trim());
                first = false;
            }
            rowStrs.add(sb.toString());
        }
        java.util.Collections.sort(rowStrs);
        return String.join("\n", rowStrs);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Caller-visible failure when correctQuery itself can't run. */
    public static class DerivationException extends RuntimeException {
        public DerivationException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public record DerivationResult(Map<Integer, String> testCaseErrors) {
        public boolean isFullySuccessful() {
            return testCaseErrors == null || testCaseErrors.isEmpty();
        }
    }
}
