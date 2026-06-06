package graduation_project_be.application.usecases.grading;

import graduation_project_be.shared.utils.TimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.GradingTrace;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TriggerMetadata;
import graduation_project_be.domain.models.TableMetadata.ColumnMetadata;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.GradingNotificationService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.SqlExecutionResult;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import graduation_project_be.domain.models.enums.VerificationType;
import graduation_project_be.domain.models.TestCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import graduation_project_be.application.usecases.GradingTraceCollector;

/** Cross-cutting grading helpers shared by all per-question-type graders. */
@Slf4j
@RequiredArgsConstructor
public class GradingSupport {

    private final ExamSchemaService examSchemaService;
    private final ObjectMapper objectMapper;
    private final TestCaseRepository testCaseRepository;

    public void executeSqlScriptBatches(String schemaName, String sqlScript) {
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

    public String normalizeDboReferences(String sql, String schemaName) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        return sql.replaceAll("(?i)\\bdbo\\s*\\.", "[" + schemaName + "].");
    }

    public List<String> splitBatchBeforeCreateRoutine(String batch) {
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

    public boolean isSyntaxErrorFailAllMode(ExamQuestion question) {
        if (question == null) {
            return false;
        }
        if (question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return false;
        }

        try {
            JsonNode root = objectMapper.readTree(question.getGradingRubric());
            String action = root
                    .path("grading_payload")
                    .path("grading_settings")
                    .path("syntax_error_action")
                    .asText("PARTIAL");
            return "FAIL_ALL".equalsIgnoreCase(action);
        } catch (Exception e) {
            log.warn("Không thể phân tích syntax_error_action cho câu {}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    public JsonNode extractInsertRuleModifiers(JsonNode ruleNode) {
        if (ruleNode == null || !ruleNode.isObject()) {
            return objectMapper.createArrayNode();
        }

        JsonNode modifiers = ruleNode.path("modifiers");
        return modifiers.isArray() ? modifiers : objectMapper.createArrayNode();
    }

    public JsonNode firstNonEmptyModifiers(JsonNode primary, JsonNode fallback) {
        if (primary != null && primary.isArray() && primary.size() > 0) {
            return primary;
        }
        if (fallback != null && fallback.isArray() && fallback.size() > 0) {
            return fallback;
        }
        return objectMapper.createArrayNode();
    }

    public boolean hasInsertModifier(JsonNode ruleNode, String expectedModifier) {
        if (expectedModifier == null || expectedModifier.isBlank()) {
            return false;
        }

        JsonNode modifiers = extractInsertRuleModifiers(ruleNode);
        for (JsonNode modifierNode : modifiers) {
            if (expectedModifier.equalsIgnoreCase(modifierNode.asText(""))) {
                return true;
            }
        }

        return false;
    }

    public boolean valuesEqualByMatchTypeWithModifiers(
            Object actualValue,
            Object expectedValue,
            String matchType,
            JsonNode modifiers,
            boolean trimSpaces,
            boolean caseInsensitive) {
        String normalizedActual = normalizeValueStr(actualValue, trimSpaces, caseInsensitive);
        String normalizedExpected = normalizeValueStr(expectedValue, trimSpaces, caseInsensitive);

        String modifiedActual = applyInsertModifiers(normalizedActual, modifiers);
        String modifiedExpected = applyInsertModifiers(normalizedExpected, modifiers);

        return valuesEqualByMatchType(modifiedActual, modifiedExpected, matchType);
    }

    public String applyInsertModifiers(String value, JsonNode modifiers) {
        if (value == null) {
            return null;
        }

        if (modifiers == null || !modifiers.isArray() || modifiers.isEmpty()) {
            return value;
        }

        String current = value;
        for (JsonNode modifierNode : modifiers) {
            String modifier = modifierNode.asText("").trim().toUpperCase(Locale.ROOT);
            if (modifier.isBlank()) {
                continue;
            }

            switch (modifier) {
                case "TO_LOWERCASE":
                    current = current.toLowerCase(Locale.ROOT);
                    break;
                case "TRIM_WHITESPACE":
                    current = current.trim();
                    break;
                case "REMOVE_ALL_WHITESPACE":
                    current = current.replaceAll("\\s+", "");
                    break;
                case "REMOVE_DIACRITICS": {
                    String normalized = Normalizer.normalize(current, Normalizer.Form.NFD);
                    current = normalized.replaceAll("\\p{M}+", "");
                    break;
                }
                case "REMOVE_SPECIAL_CHARS":
                    current = current.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", "");
                    break;
                case "CAST_TO_STRING":
                    current = String.valueOf(current);
                    break;
                case "CAST_TO_FLOAT":
                    current = canonicalizeNumber(current, -1);
                    break;
                case "ROUND_TO_INT":
                    current = canonicalizeNumber(current, 0);
                    break;
                case "ROUND_2_DECIMALS":
                    current = canonicalizeNumber(current, 2);
                    break;
                case "SORT_ASC":
                    current = sortTokensAscending(current);
                    break;
                default:
                    break;
            }
        }

        return current;
    }

    public String sortTokensAscending(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return trimmed;
        }

        boolean commaSeparated = trimmed.contains(",");
        String[] rawTokens = commaSeparated
                ? trimmed.split("\\s*,\\s*")
                : trimmed.split("\\s+");
        if (rawTokens.length <= 1) {
            return trimmed;
        }

        List<String> tokens = new ArrayList<>();
        for (String rawToken : rawTokens) {
            if (rawToken == null) {
                continue;
            }
            String token = rawToken.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }

        if (tokens.size() <= 1) {
            return trimmed;
        }

        tokens.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(commaSeparated ? "," : " ", tokens);
    }

    public String canonicalizeNumber(String value, int targetScale) {
        if (value == null) {
            return null;
        }

        BigDecimal decimal = parseDecimal(value.trim());
        if (decimal == null) {
            return value;
        }

        if (targetScale >= 0) {
            decimal = decimal.setScale(targetScale, RoundingMode.HALF_UP);
        }

        return decimal.stripTrailingZeros().toPlainString();
    }

    public boolean isNullLike(String val) {
        return val == null || val.isEmpty() || "null".equalsIgnoreCase(val);
    }

    public String normalizeValueStr(Object val, boolean trimSpaces, boolean caseInsensitive) {
        if (val == null)
            return null;
        String s = String.valueOf(val);
        if (trimSpaces)
            s = s.trim();
        if (caseInsensitive)
            s = s.toLowerCase();
        return s;
    }

    public Object getRowValueIgnoreCase(Map<String, Object> row, String columnName) {
        if (row == null || columnName == null) {
            return null;
        }

        if (row.containsKey(columnName)) {
            return row.get(columnName);
        }

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    public boolean valuesEqual(String actual, String expected) {
        if (java.util.Objects.equals(actual, expected)) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }

        BigDecimal aNum = parseDecimal(actual);
        BigDecimal eNum = parseDecimal(expected);
        if (aNum != null && eNum != null) {
            return aNum.compareTo(eNum) == 0;
        }

        return false;
    }

    public boolean valuesEqualByMatchType(String actual, String expected, String matchType) {
        String normalizedMatchType = matchType == null ? "EXACT" : matchType.trim().toUpperCase(Locale.ROOT);
        switch (normalizedMatchType) {
            case "IGNORE_CASE_AND_SPACE":
                String actualIgnoreCase = normalizeValueStr(actual, true, true);
                String expectedIgnoreCase = normalizeValueStr(expected, true, true);
                return valuesEqual(actualIgnoreCase, expectedIgnoreCase);
            case "NUMERIC_TOLERANCE":
                BigDecimal aNum = parseDecimal(actual);
                BigDecimal eNum = parseDecimal(expected);
                if (aNum != null && eNum != null) {
                    BigDecimal diff = aNum.subtract(eNum).abs();
                    return diff.compareTo(new BigDecimal("0.000001")) <= 0;
                }
                return valuesEqual(actual, expected);
            case "EXACT":
            default:
                return valuesEqual(actual, expected);
        }
    }

    public BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean readBooleanSetting(JsonNode node, boolean defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asInt() != 0;
        }
        if (node.isTextual()) {
            String value = node.asText("").trim().toLowerCase();
            if ("true".equals(value) || "1".equals(value) || "yes".equals(value) || "y".equals(value)
                    || "on".equals(value)) {
                return true;
            }
            if ("false".equals(value) || "0".equals(value) || "no".equals(value) || "n".equals(value)
                    || "off".equals(value)) {
                return false;
            }
        }
        return defaultValue;
    }

    public double readDoubleSetting(JsonNode node, double defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().trim());
            } catch (Exception ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public JsonNode findInsertRule(JsonNode gradingRules, String target, String condition) {
        if (!gradingRules.isArray()) {
            return null;
        }

        for (JsonNode ruleNode : gradingRules) {
            if (!ruleNode.isObject()) {
                continue;
            }

            String ruleTarget = ruleNode.path("target").asText("").trim();
            String ruleCondition = ruleNode.path("condition").asText("").trim();
            if (target.equalsIgnoreCase(ruleTarget) && condition.equalsIgnoreCase(ruleCondition)) {
                return ruleNode;
            }
        }

        return null;
    }

    public void addTeacherConfigTrace(
            String status,
            String label,
            String message,
            BigDecimal maxPoints,
            String configSummary) {
        if (!GradingTraceCollector.isActive()) {
            return;
        }

        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_TEACHER_CONFIG,
                status,
                label,
                message,
                null,
                null,
                "TEACHER_CONFIG",
                "MISSING",
                "REVIEW_CONFIG",
                null,
                null,
                maxPoints,
                null,
                null,
                null,
                configSummary));
    }

    public boolean gradeByTestCases(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission) {
        List<TestCase> testCases = testCaseRepository.findByQuestionId(question.getId());
        log.info("[gradeByTestCases] Câu {} có {} test case", question.getId(),
                testCases == null ? 0 : testCases.size());

        if (testCases == null || testCases.isEmpty()) {
            // Fail-loud for DDL types: surface the "missing rubric/test cases" reason
            // on the submission so the teacher knows the question needs setup.
            QuestionType type = question.getQuestionType();
            BigDecimal questionPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
            addTeacherConfigTrace(
                    GradingTraceItem.STATUS_WARN,
                    "Thiếu test case",
                    "[THIẾU TEST CASE] Câu hỏi này chưa có test case nào trong DB.",
                    questionPoints,
                    "Không có test case cho " + type + "; hệ thống fallback sang strict comparison nếu có thể.");
            if (submission != null && (type == QuestionType.STORED_PROCEDURE
                    || type == QuestionType.FUNCTION
                    || type == QuestionType.TRIGGER)) {
                submission.setErrorMessage(
                        "[THIẾU TEST CASE] Câu hỏi này chưa có test case nào trong DB. "
                                + "Hãy chạy pipeline tạo rubric (T08/T09) hoặc thêm test case thủ công. "
                                + "Điểm hiện tại chỉ phản ánh phần kiểm tra metadata.");
            }
            return gradeByStrictComparison(schemaName, question);
        }

        boolean useDeductionScoring = question.getQuestionType() == QuestionType.STORED_PROCEDURE;
        BigDecimal earnedTotal = useDeductionScoring ? BigDecimal.ONE : BigDecimal.ZERO;
        boolean allPassed = true;
        StringBuilder errorBuilder = new StringBuilder();
        String printOutputCompareMode = readPrintOutputCompareMode(question);

        for (TestCase tc : testCases) {
            int tcOrder = tc.getOrderIndex() != null ? tc.getOrderIndex() : 0;
            BigDecimal caseWeight = tc.getScoreWeight() != null ? tc.getScoreWeight() : BigDecimal.ZERO;
            try {
                TestCaseRunResult run = runOneTestCase(schemaName, teacherSchemaName, tc);
                String actualSerialized = run.actualValue;
                String expected = tc.getExpectedValue() != null ? tc.getExpectedValue().trim() : "";

                boolean isTcCorrect = compareWithMatchType(actualSerialized, expected, tc, printOutputCompareMode);

                log.info("[gradeByTestCases] Câu {} TC{} ({}): thực tế='{}' mong đợi='{}' khớp={}",
                        question.getId(), tcOrder,
                        tc.getVerificationType(),
                        truncateForLog(actualSerialized), truncateForLog(expected), isTcCorrect);

                if (isTcCorrect) {
                    if (!useDeductionScoring) {
                        earnedTotal = earnedTotal.add(caseWeight);
                    }
                } else {
                    allPassed = false;
                    if (useDeductionScoring) {
                        earnedTotal = earnedTotal.subtract(caseWeight);
                    }
                    String tcLabel = tc.getCaseName() != null ? tc.getCaseName() : "TC" + tcOrder;
                errorBuilder.append(String.format("[%s] mong đợi='%s', thực tế='%s'. ",
                            tcLabel, truncateForLog(expected), truncateForLog(actualSerialized)));
                }
                if (GradingTraceCollector.isActive()) {
                    String tcLabel = tc.getCaseName() != null ? tc.getCaseName() : "TC" + tcOrder;
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_TEST_CASE,
                            isTcCorrect ? GradingTraceItem.STATUS_PASS : GradingTraceItem.STATUS_FAIL,
                            tcLabel,
                            isTcCorrect ? "Test case đạt" : "Kết quả không khớp",
                            tc.getId() != null ? tc.getId().toString() : null,
                            tcLabel,
                            null, null, null, null,
                            isTcCorrect ? caseWeight : BigDecimal.ZERO,
                            caseWeight,
                            isTcCorrect ? null : caseWeight,
                            truncateForLog(expected),
                            truncateForLog(actualSerialized),
                            (tc.getVerificationType() != null ? tc.getVerificationType().name() : "")
                                    + " (trọng số, điểm tuyệt đối = trọng số × điểm câu × tỷ lệ TC)"));
                }
            } catch (Exception e) {
                allPassed = false;
                if (useDeductionScoring) {
                    earnedTotal = earnedTotal.subtract(caseWeight);
                }
                String tcLabel = tc.getCaseName() != null ? tc.getCaseName() : "TC" + tcOrder;
                errorBuilder.append(String.format("[%s] lỗi khi chạy test case: %s. ", tcLabel, e.getMessage()));
                log.error("[gradeByTestCases] Câu {} TC{} phát sinh lỗi: {}",
                        question.getId(), tcOrder, e.getMessage(), e);
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_TEST_CASE,
                            GradingTraceItem.STATUS_FAIL,
                            tcLabel,
                            "Lỗi khi chạy test case: " + e.getMessage(),
                            tc.getId() != null ? tc.getId().toString() : null,
                            tcLabel,
                            null, null, null, null,
                            BigDecimal.ZERO, caseWeight, caseWeight,
                            null, null,
                            (tc.getVerificationType() != null ? tc.getVerificationType().name() : "")
                                    + " (trọng số, điểm tuyệt đối = trọng số × điểm câu × tỷ lệ TC)"));
                }
            }
        }
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0) {
            earnedTotal = BigDecimal.ZERO;
        } else if (earnedTotal.compareTo(BigDecimal.ONE) > 0) {
            earnedTotal = BigDecimal.ONE;
        }
        log.info("[gradeByTestCases] Câu {} tất cả đạt={} tổng trọng số đạt={}", question.getId(), allPassed,
                earnedTotal);

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        return allPassed;
    }

    /**
     * Runs one test case against the student's schema, dispatching by
     * {@link VerificationType}. The sequence setup → invocation → validation is
     * executed inside a SQL Server BEGIN TRAN / ROLLBACK TRAN block so that any
     * INSERT/UPDATE/DELETE side effects (especially common for SIDE_EFFECT and
     * Trigger TC's) do not leak to the next TC.
     *
     * <p>Note on connection: ROLLBACK must run on the same connection as BEGIN
     * TRAN. We achieve that by sending the whole script as ONE batch via
     * executeSqlBatchAsSchemaUser (a single jdbcTemplate.execute call uses one
     * connection). The validation_query's result set is read by the engine
     * BEFORE the ROLLBACK statement clears it — standard MSSQL pattern.
     *
     * <p>Note on impersonation (P0-2): the batch runs under EXECUTE AS USER for
     * the student's schema-scoped DB user, NOT under the admin connection. This
     * is what stops a malicious or buggy student SP from reading other
     * students' schemas. executeSqlBatchAsSchemaUser also enforces
     * QUERY_TIMEOUT_SECONDS (P0-3) so an infinite loop / WAITFOR cannot hang
     * the grading worker.
     */
    public TestCaseRunResult runOneTestCase(String schemaName, String teacherSchemaName, TestCase tc) {
        VerificationType type = tc.getVerificationType() != null
                ? tc.getVerificationType()
                : VerificationType.RETURN_VALUE;

        String setup = applyPlaceholders(tc.getSetupScript(), schemaName, teacherSchemaName);
        String invocation = applyPlaceholders(tc.getInvocationQuery(), schemaName, teacherSchemaName);
        String validation = applyPlaceholders(tc.getValidationQuery(), schemaName, teacherSchemaName);

        // Build a single SQL batch that wraps the whole TC in a transaction.
        // Why TRY/CATCH: if any inner statement throws, we still want a clean
        // ROLLBACK and a thrown exception (the catch re-throws via THROW).
        StringBuilder batch = new StringBuilder();
        batch.append("BEGIN TRY\n");
        batch.append("  BEGIN TRANSACTION;\n");
        if (setup != null && !setup.isBlank()) {
            batch.append("  ").append(setup).append(";\n");
        }
        if (invocation != null && !invocation.isBlank()) {
            batch.append("  ").append(invocation).append(";\n");
        }
        if (validation != null && !validation.isBlank()) {
            // P1-2: emit a marker result set right before validation_query.
            // If invocation_query unintentionally produced result sets (e.g. an
            // SP whose body has SELECT statements), executeSqlBatchAsSchemaUser
            // would concatenate them together with validation rows. The marker
            // lets us drop everything before validation when serializing.
            batch.append("  SELECT NULL AS ").append(VALIDATION_MARKER_COLUMN).append(";\n");
            batch.append("  ").append(validation).append(";\n");
        }
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append("END TRY\n");
        batch.append("BEGIN CATCH\n");
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append("  THROW;\n");
        batch.append("END CATCH;");

        // Run as the student's schema-scoped DB user (NOT admin) so that any
        // student-defined routine called inside the batch is restricted to its
        // own schema's permissions. Also enforces query timeout.
        SqlExecutionResult execResult = examSchemaService.executeSqlBatchAsSchemaUser(schemaName, batch.toString());

        // Capture per verification_type — must match ExpectedValueDeriver.serializeResult
        // exactly so EXACT compare works.
        String actual;
        if (type == VerificationType.PRINT_OUTPUT) {
            List<String> prints = execResult.getPrintMessages() != null
                    ? execResult.getPrintMessages()
                    : new ArrayList<>();
            actual = String.join("\n", prints).trim();
        } else {
            actual = serializeResultForCompare(dropRowsBeforeValidationMarker(execResult));
        }
        return new TestCaseRunResult(actual);
    }

    /** Column name used to mark the start of validation_query's result set. */
    private static final String VALIDATION_MARKER_COLUMN = "__VALIDATION_MARKER__";

    /**
     * Returns a copy of {@code execResult} with all rows up to AND including the
     * marker row removed. If no marker row is present (e.g. test case has no
     * validation_query, or marker was added by a different layer), returns the
     * original result unchanged.
     */
    public SqlExecutionResult dropRowsBeforeValidationMarker(SqlExecutionResult execResult) {
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
     * Mirrors ExpectedValueDeriver.serializeResult — both must agree on the
     * canonical string form, otherwise EXACT compare always fails.
     */
    public String serializeResultForCompare(SqlExecutionResult result) {
        if (result == null || result.getResultSet() == null || result.getResultSet().isEmpty()) {
            return "";
        }
        List<Map<String, Object>> rows = result.getResultSet();
        if (rows.size() == 1 && rows.get(0).size() == 1) {
            Object v = rows.get(0).values().iterator().next();
            return v == null ? "null" : v.toString().trim();
        }
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
        Collections.sort(rowStrs);
        return String.join("\n", rowStrs);
    }

    public boolean compareWithMatchType(String actual, String expected, TestCase tc, String printOutputCompareMode) {
        if (actual == null) actual = "";
        if (expected == null) expected = "";
        actual = actual.trim();
        expected = expected.trim();

        if (tc.getVerificationType() == VerificationType.PRINT_OUTPUT
                && "LENIENT".equalsIgnoreCase(printOutputCompareMode)) {
            actual = normalizePrintOutputForCompare(actual);
            expected = normalizePrintOutputForCompare(expected);
        }

        graduation_project_be.domain.models.enums.MatchType match = tc.getMatchType() != null
                ? tc.getMatchType()
                : graduation_project_be.domain.models.enums.MatchType.EXACT;
        if (tc.getVerificationType() == VerificationType.PRINT_OUTPUT
                && match == graduation_project_be.domain.models.enums.MatchType.CONTAINS) {
            return actual.toLowerCase().contains(expected.toLowerCase());
        }
        // EXACT (default)
        return actual.equalsIgnoreCase(expected);
    }

    public String readPrintOutputCompareMode(ExamQuestion question) {
        if (question == null || question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return "LENIENT";
        }
        try {
            JsonNode root = objectMapper.readTree(question.getGradingRubric());
            return root.path("grading_payload")
                    .path("grading_settings")
                    .path("print_output_compare_mode")
                    .asText("LENIENT");
        } catch (Exception e) {
            log.warn("Không thể phân tích print_output_compare_mode cho câu {}: {}", question.getId(), e.getMessage());
            return "LENIENT";
        }
    }

    public String normalizePrintOutputForCompare(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public String applyPlaceholders(String sql, String schemaName, String teacherSchemaName) {
        if (sql == null) return null;
        String result = sql;
        if (schemaName != null) result = result.replace("{SCHEMA}", schemaName);
        if (teacherSchemaName != null) result = result.replace("{TEACHER_SCHEMA}", teacherSchemaName);
        // [dbo] / dbo. is a recurring AI hallucination from REFERENCE SQL.
        // The grading engine runs every batch inside a per-user schema, never dbo,
        // so hardcoded dbo always fails with "Could not find stored procedure".
        // Coerce to the target schema; safe because no question in this system
        // intentionally targets dbo objects.
        if (schemaName != null) {
            result = result.replaceAll("(?i)\\[dbo\\]\\s*\\.", "[" + schemaName + "].");
            result = result.replaceAll("(?i)\\bdbo\\s*\\.", "[" + schemaName + "].");
        }
        return result;
    }

    public static String truncateForLog(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private record TestCaseRunResult(String actualValue) {
    }

    public boolean gradeByStrictComparison(String schemaName, ExamQuestion question) {
        // Fail-loud for DDL-style questions. correctQuery for these types is
        // CREATE PROC/FUNCTION/TRIGGER — re-running it on the student schema
        // either throws "object already exists" (if the student got it right)
        // or returns no result set (which we'd interpret as wrong answer).
        // Either way, strict comparison is the wrong semantics for DDL.
        // Instead we surface a clear "missing rubric/test cases" message so the
        // teacher knows to add test cases for this question.
        QuestionType type = question.getQuestionType();
        if (type == QuestionType.STORED_PROCEDURE
                || type == QuestionType.FUNCTION
                || type == QuestionType.TRIGGER) {
            log.error("[gradeByStrictComparison] Câu {} ({}) không có test case và không có rubric dùng được — "
                    + "không thể fallback sang so sánh nghiêm ngặt cho loại DDL. Điểm sẽ dựa trên "
                    + "kiểm tra chỉ metadata (và có thể là 0 nếu metadata cũng thiếu).",
                    question.getId(), type);
            return false;
        }

        try {
            List<Map<String, Object>> actual = examSchemaService.executeSql(
                    schemaName, question.getCorrectQuery()).getResultSet();

            String verifyScript = question.getVerifyScript();
            if (verifyScript == null || verifyScript.isBlank()) {
                log.warn("Câu {} không có verify_script nên không thể so sánh nghiêm ngặt", question.getId());
                return actual != null && !actual.isEmpty();
            }

            List<Map<String, Object>> expected = examSchemaService.executeSql(
                    schemaName, verifyScript).getResultSet();

            boolean requireStrictOrder = question.getCorrectQuery() != null
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            return compareResultSetsStrict(actual, expected, requireStrictOrder);
        } catch (Exception e) {
            log.warn("Chấm so sánh nghiêm ngặt thất bại cho câu {}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    public boolean compareResultSetsStrict(List<Map<String, Object>> actual,
            List<Map<String, Object>> expected, boolean requireStrictOrder) {
        if (actual == null || expected == null)
            return false;
        if (actual.size() != expected.size())
            return false;

        List<String> actualRows = new ArrayList<>();
        for (Map<String, Object> row : actual) {
            StringBuilder sb = new StringBuilder();
            for (Object val : row.values()) {
                sb.append(normalizeValue(val)).append("|||");
            }
            actualRows.add(sb.toString().toLowerCase());
        }

        List<String> expectedRows = new ArrayList<>();
        for (Map<String, Object> row : expected) {
            StringBuilder sb = new StringBuilder();
            for (Object val : row.values()) {
                sb.append(normalizeValue(val)).append("|||");
            }
            expectedRows.add(sb.toString().toLowerCase());
        }

        if (!requireStrictOrder) {
            Collections.sort(actualRows);
            Collections.sort(expectedRows);
        }

        return actualRows.equals(expectedRows);
    }

    public String normalizeValue(Object value) {
        if (value == null)
            return "null";
        String str = value.toString().trim();
        if (str.matches("-?\\d+\\.\\d+")) {
            str = str.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return str;
    }

    public void setAllConstraintsEnabled(String schemaName, boolean enabled) {
        String safeSchema = schemaName.replaceAll("[^a-zA-Z0-9_]", "");
        List<Map<String, Object>> tables = examSchemaService.executeAdminSql(
                "SELECT t.name AS TABLE_NAME "
                        + "FROM sys.tables t "
                        + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
                        + "WHERE s.name = '" + safeSchema + "'")
                .getResultSet();

        for (Map<String, Object> row : tables) {
            Object tableNameObj = row.get("TABLE_NAME");
            if (tableNameObj == null) {
                continue;
            }

            String tableName = String.valueOf(tableNameObj).replaceAll("[^a-zA-Z0-9_]", "");
            String sql = enabled
                    ? "ALTER TABLE [" + safeSchema + "].[" + tableName + "] WITH CHECK CHECK CONSTRAINT ALL"
                    : "ALTER TABLE [" + safeSchema + "].[" + tableName + "] NOCHECK CONSTRAINT ALL";

            try {
                examSchemaService.executeAdminSql(sql);
            } catch (Exception e) {
                log.warn("Không thể {} ràng buộc cho bảng {}: {}",
                        enabled ? "bật" : "tắt", tableName, e.getMessage());
            }
        }
    }
}
