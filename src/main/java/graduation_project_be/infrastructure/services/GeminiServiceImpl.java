package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graduation_project_be.application.port.services.GeminiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GeminiServiceImpl implements GeminiService {

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=";
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?is)create\\s+table\\s+([\\[\\]A-Za-z0-9_\\.]+)\\s*\\(");
    private static final Pattern CONSTRAINT_PREFIX_PATTERN = Pattern.compile(
            "(?is)^constraint\\s+[^\\s]+\\s+");
    private static final Pattern REFERENCES_PATTERN = Pattern.compile(
            "(?is)references\\s+([\\[\\]A-Za-z0-9_\\.]+)\\s*\\(([^\\)]*)\\)");
    private static final double CT_MISSING_COLUMN_PENALTY = 0.5d;
    private static final double CT_TYPE_MISMATCH_PENALTY = 0.25d;
    private static final double CT_MISSING_PK_FK_PENALTY = 0.5d;
    private static final double[] CT_TABLE_PENALTY_STEPS = new double[] {0.25d, 0.2d};

    private final String apiKey;
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("classpath:prompts/system_prompt.txt")
    private Resource systemPromptResource;

    @Value("classpath:prompts/create_table_rubric_prompt.txt")
    private Resource createTableRubricPromptResource;

    @Value("classpath:prompts/insert_data_rubric_prompt.txt")
    private Resource insertDataRubricPromptResource;

    @Value("classpath:prompts/select_query_rubric_prompt.txt")
    private Resource selectQueryRubricPromptResource;

    @Value("classpath:prompts/create_table_rules_prompt.txt")
    private Resource createTableRulesPromptResource;

    private String systemPromptTemplate;
    private String createTableRubricPromptTemplate;
    private String insertDataRubricPromptTemplate;
    private String selectQueryRubricPromptTemplate;
    private String createTableRulesPromptTemplate;

    public GeminiServiceImpl(@Value("${spring.application.gemini.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = null;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        try {
            this.systemPromptTemplate = StreamUtils.copyToString(systemPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.createTableRubricPromptTemplate = StreamUtils.copyToString(createTableRubricPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.insertDataRubricPromptTemplate = StreamUtils.copyToString(insertDataRubricPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.selectQueryRubricPromptTemplate = StreamUtils.copyToString(selectQueryRubricPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.createTableRulesPromptTemplate = StreamUtils.copyToString(createTableRulesPromptResource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load Gemini prompt templates from resources/prompts", e);
            throw new RuntimeException("Failed to load Gemini prompt templates", e);
        }
    }

    @Override
    public GeneratedQuestion generateSqlAnswer(String questionContent, String questionType, String schemaContext) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key is missing. Skipping AI generation.");
            return new GeneratedQuestion("-- AI generation unavailable: missing Gemini API key", null);
        }

        HttpClient client = getOrCreateHttpClient();
        if (client == null) {
            return new GeneratedQuestion("-- AI generation unavailable: HTTP client initialization failed", null);
        }

        String prompt = buildPrompt(questionContent, questionType, schemaContext);
        String requestBody = buildRequestBody(prompt);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_URL + apiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Gemini API error {}: {}", response.statusCode(), response.body());
                return new GeneratedQuestion("-- AI generation failed", null);
            }

            return parseResponse(response.body());

        } catch (Exception e) {
            log.error("Failed to call Gemini API: {}", e.getMessage(), e);
            return new GeneratedQuestion("-- AI generation failed: " + e.getMessage(), null);
        }
    }

    private HttpClient getOrCreateHttpClient() {
        if (httpClient != null) {
            return httpClient;
        }

        synchronized (this) {
            if (httpClient != null) {
                return httpClient;
            }
            try {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .build();
                return httpClient;
            } catch (Exception e) {
                log.error("Failed to initialize HTTP client for Gemini: {}", e.getMessage(), e);
                return null;
            }
        }
    }

    private String buildPrompt(String questionContent, String questionType, String schemaContext) {
        return String.format(systemPromptTemplate,
                schemaContext != null && !schemaContext.isBlank() ? schemaContext : "Không có thông tin schema",
                questionType,
                questionContent);
    }

    private String buildRequestBody(String prompt) {
        return buildRequestBody(prompt, 1024);
    }

    private String buildRequestBody(String prompt, int maxOutputTokens) {
        try {
            String escaped = objectMapper.writeValueAsString(prompt);
            // escaped includes surrounding quotes, remove them
            escaped = escaped.substring(1, escaped.length() - 1);
            return String.format("""
                    {
                      "contents": [{"parts": [{"text": "%s"}]}],
                      "generationConfig": {
                        "temperature": 0.1,
                        "maxOutputTokens": %d
                      }
                    }
                    """, escaped, maxOutputTokens);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gemini request body", e);
        }
    }

    private GeneratedQuestion parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String text = root
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            // Strip markdown code blocks if present
            text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            JsonNode result = objectMapper.readTree(text);
            String correctQuery = result.path("correctQuery").asText("-- no query generated");
            String verifyScript = result.path("verifyScript").asText(null);

            return new GeneratedQuestion(correctQuery, verifyScript.isBlank() ? null : verifyScript);

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            return new GeneratedQuestion("-- failed to parse AI response", null);
        }
    }

    @Override
    public String generateGradingRubric(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String questionType,
            String priorQuestionContext) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key is missing. Cannot generate rubric.");
            return null;
        }

        HttpClient client = getOrCreateHttpClient();
        if (client == null) return null;

        String basePrompt;
        if ("CREATE_TABLE_RULES".equalsIgnoreCase(questionType)) {
            basePrompt = buildCreateTableRulesPrompt(correctQuery, questionContent, totalPoints);
        } else if ("INSERT_DATA".equalsIgnoreCase(questionType)) {
            basePrompt = buildInsertRubricPrompt(correctQuery, questionContent, totalPoints);
        } else if ("SELECT_QUERY".equalsIgnoreCase(questionType)) {
            basePrompt = buildSelectRubricPrompt(correctQuery, questionContent, totalPoints, priorQuestionContext);
        } else {
            basePrompt = buildCreateTableRubricPrompt(correctQuery, questionContent, totalPoints);
        }

        try {
            if ("CREATE_TABLE_RULES".equalsIgnoreCase(questionType)) {
                String rubricJson = callGeminiForJson(client, basePrompt);
                if (rubricJson == null) {
                    return null;
                }
                logGeneratedRubric(questionType, rubricJson);
                return rubricJson;
            }

            if ("CREATE_TABLE".equalsIgnoreCase(questionType)) {
                String prompt = basePrompt;
                String latestJson = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    latestJson = callGeminiForJson(client, prompt);
                    if (latestJson == null) {
                        return null;
                    }

                    JsonNode rubricNode = objectMapper.readTree(latestJson);
                    List<String> issues = validateCreateTableRubricHeuristics(correctQuery, rubricNode);
                    if (issues.isEmpty()) {
                        String normalizedRubric = applyCreateTablePenaltyDefaults(rubricNode, totalPoints);
                        logGeneratedRubric(questionType, normalizedRubric);
                        return normalizedRubric;
                    }

                    if (attempt == 2) {
                        log.warn("CREATE_TABLE rubric still has structural issues after retries: {}", issues);
                        logGeneratedRubric(questionType, latestJson);
                        return null;
                    }

                    prompt = basePrompt + "\n\n=== REQUIRED FIXES ===\n"
                            + String.join("\n", issues)
                            + "\nReturn corrected JSON only. Do not omit any tables, columns, PRIMARY_KEY, or FOREIGN_KEY that appear in the sample answer SQL.";
                }

                return latestJson;
            }

            if ("INSERT_DATA".equalsIgnoreCase(questionType)) {
                String rubricJson = callGeminiForJson(client, basePrompt);
                if (rubricJson == null) {
                    return null;
                }
                String normalizedRubric = normalizeInsertRubricSchema(rubricJson, totalPoints);
                logGeneratedRubric(questionType, normalizedRubric);
                return normalizedRubric;
            }

            if (!"SELECT_QUERY".equalsIgnoreCase(questionType)) {
                String rubricJson = callGeminiForJson(client, basePrompt);
                logGeneratedRubric(questionType, rubricJson);
                return rubricJson;
            }

            String prompt = basePrompt;
            String latestJson = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                latestJson = callGeminiForJson(client, prompt);
                if (latestJson == null) {
                    return null;
                }

                JsonNode rubricNode = objectMapper.readTree(latestJson);
                List<String> issues = validateSelectRubricHeuristics(rubricNode);
                if (issues.isEmpty()) {
                    logGeneratedRubric(questionType, latestJson);
                    return latestJson;
                }

                if (attempt == 2) {
                    log.warn("SELECT rubric still has heuristic issues after retries: {}", issues);
                    logGeneratedRubric(questionType, latestJson);
                    return latestJson;
                }

                prompt = basePrompt + "\n\n=== FEEDBACK BẮT BUỘC SỬA ===\n"
                    + String.join("\n", issues)
                    + "\nHãy trả về JSON mới hoàn chỉnh, chỉ JSON, không giải thích.";
            }

            return latestJson;

        } catch (Exception e) {
            log.error("Failed to generate grading rubric: {}", e.getMessage(), e);
            return null;
        }
    }

    private String callGeminiForJson(HttpClient client, String prompt) throws Exception {
        String requestBody = buildRequestBody(prompt, 4096);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(GEMINI_URL + apiKey))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(60))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Gemini API error {}: {}", response.statusCode(), response.body());
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());
        String text = root
            .path("candidates").get(0)
            .path("content")
            .path("parts").get(0)
            .path("text").asText();

        text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        objectMapper.readTree(text);
        return text;
    }

    private void logGeneratedRubric(String questionType, String rubricJson) {
        if (rubricJson == null || rubricJson.isBlank()) {
            log.warn("Gemini returned empty rubric for questionType={}", questionType);
            return;
        }

        try {
            JsonNode rubricNode = objectMapper.readTree(rubricJson);
            String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rubricNode);
            log.info("Gemini generated rubric for questionType={}:\n{}", questionType, prettyJson);
        } catch (Exception e) {
            log.warn("Gemini generated rubric for questionType={} but pretty logging failed. Raw rubric: {}",
                    questionType, rubricJson);
        }
    }

    private List<String> validateSelectRubricHeuristics(JsonNode rubricNode) {
        List<String> issues = new ArrayList<>();
        JsonNode testCases = rubricNode.path("grading_payload").path("test_cases");

        if (!testCases.isArray() || testCases.size() < 4) {
            issues.add("- Phải có ít nhất 4 test case hợp lệ.");
            return issues;
        }

        for (int i = 0; i < testCases.size(); i++) {
            JsonNode tc = testCases.get(i);
            String caseId = tc.path("case_id").asText("TC_" + (i + 1));
            String dep = tc.path("setup_dependency_id").asText("").trim();
            String script = tc.path("setup_custom_script").asText("");
            String upper = script.toUpperCase();

            if (!dep.isBlank() && !dep.matches("\\d+")) {
                issues.add("- " + caseId + ": setup_dependency_id phải để rỗng hoặc là ID số.");
            }

            if (upper.contains("CREATE TABLE") || upper.contains("DROP TABLE")) {
                issues.add("- " + caseId + ": setup_custom_script không được chứa CREATE TABLE hoặc DROP TABLE.");
            }
        }

        return issues;
    }

    private List<String> validateCreateTableRubricHeuristics(String correctQuery, JsonNode rubricNode) {
        List<String> issues = new ArrayList<>();
        Map<String, ParsedCreateTable> expectedTables = parseCreateTableSql(correctQuery);
        JsonNode rubricTables = rubricNode.path("grading_payload").path("tables");

        if (expectedTables.isEmpty()) {
            issues.add("- Could not parse CREATE TABLE structure from the sample answer SQL. Return every table and every column from the SQL.");
            return issues;
        }

        if (!rubricTables.isArray()) {
            issues.add("- grading_payload.tables must be an array.");
            return issues;
        }

        for (ParsedCreateTable expectedTable : expectedTables.values()) {
            JsonNode rubricTable = findRubricTable(rubricTables, expectedTable.tableName());
            if (rubricTable == null) {
                issues.add("- Missing table in rubric: " + expectedTable.tableName());
                continue;
            }

            JsonNode rubricColumns = rubricTable.path("columns");
            if (!rubricColumns.isArray()) {
                issues.add("- Table " + expectedTable.tableName() + " must contain a columns array.");
                continue;
            }

            if (!expectedTable.columns().isEmpty() && rubricColumns.size() == 0) {
                issues.add("- Table " + expectedTable.tableName()
                        + " has columns in the sample SQL but columns[] is empty in the rubric.");
            }

            for (String expectedColumn : expectedTable.columns()) {
                if (!rubricHasColumn(rubricColumns, expectedColumn)) {
                    issues.add("- Table " + expectedTable.tableName()
                            + " is missing column in rubric: " + expectedColumn);
                }
            }

            JsonNode rubricConstraints = rubricTable.path("constraints");
            for (List<String> primaryKeyColumns : expectedTable.primaryKeys()) {
                if (!rubricHasConstraint(rubricConstraints, "PRIMARY_KEY", primaryKeyColumns, null, null)) {
                    issues.add("- Table " + expectedTable.tableName()
                            + " is missing PRIMARY_KEY in rubric for columns: "
                            + String.join(", ", primaryKeyColumns));
                }
            }

            for (ParsedForeignKey foreignKey : expectedTable.foreignKeys()) {
                if (!rubricHasConstraint(
                        rubricConstraints,
                        "FOREIGN_KEY",
                        foreignKey.columns(),
                        foreignKey.referencesTable(),
                        foreignKey.referencesColumns())) {
                    issues.add("- Table " + expectedTable.tableName()
                            + " is missing FOREIGN_KEY in rubric for columns: "
                            + String.join(", ", foreignKey.columns()));
                }
            }
        }

        return issues;
    }

    private boolean approximatelyEqual(double left, double right) {
        return Math.abs(left - right) <= 0.01d;
    }

    private String applyCreateTablePenaltyDefaults(JsonNode rubricNode, double totalPoints) throws Exception {
        if (!(rubricNode instanceof ObjectNode root)) {
            return objectMapper.writeValueAsString(rubricNode);
        }

        root.put("total_points", totalPoints);
        root.put("question_category", "CREATE_TABLE");

        ObjectNode gradingPayload = ensureObject(root, "grading_payload");
        ObjectNode gradingSettings = ensureObject(gradingPayload, "grading_settings");
        gradingSettings.put("syntax_error_action", "PARTIAL");
        gradingSettings.put("case_sensitive_names", false);
        gradingSettings.put("allow_implicit_constraints", true);
        gradingSettings.put("positive_only_scoring", false);
        gradingSettings.put("skip_child_checks_when_table_missing", true);

        ArrayNode tables = ensureArray(gradingPayload, "tables");
        if (tables.isEmpty()) {
            return objectMapper.writeValueAsString(root);
        }

        double[] tablePenalties = allocateMissingTablePenalties(tables, totalPoints);
        for (int i = 0; i < tables.size(); i++) {
            JsonNode tableNode = tables.get(i);
            if (!(tableNode instanceof ObjectNode table)) {
                continue;
            }

            table.put("missing_table_penalty", tablePenalties[i]);
            table.put("missing_penalty_action", "SKIP_TABLE");

            ArrayNode columns = ensureArray(table, "columns");
            for (JsonNode columnNode : columns) {
                if (columnNode instanceof ObjectNode column) {
                    column.put("missing_column_penalty", CT_MISSING_COLUMN_PENALTY);
                    column.put("type_mismatch_penalty", CT_TYPE_MISMATCH_PENALTY);
                }
            }

            ArrayNode constraints = ensureArray(table, "constraints");
            for (JsonNode constraintNode : constraints) {
                if (!(constraintNode instanceof ObjectNode constraint)) {
                    continue;
                }
                String type = constraint.path("type").asText("");
                if ("PRIMARY_KEY".equalsIgnoreCase(type) || "FOREIGN_KEY".equalsIgnoreCase(type)) {
                    constraint.put("missing_constraint_penalty", CT_MISSING_PK_FK_PENALTY);
                }
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    private double[] allocateMissingTablePenalties(ArrayNode tables, double totalPoints) {
        int tableCount = tables.size();
        double[] penalties = new double[tableCount];
        if (tableCount == 0) {
            return penalties;
        }

        int[] weights = new int[tableCount];
        int totalWeight = 0;
        for (int i = 0; i < tableCount; i++) {
            JsonNode table = tables.get(i);
            int columnCount = table.path("columns").isArray() ? table.path("columns").size() : 0;
            int pkFkCount = 0;
            JsonNode constraints = table.path("constraints");
            if (constraints.isArray()) {
                for (JsonNode constraint : constraints) {
                    String type = constraint.path("type").asText("");
                    if ("PRIMARY_KEY".equalsIgnoreCase(type) || "FOREIGN_KEY".equalsIgnoreCase(type)) {
                        pkFkCount++;
                    }
                }
            }
            int weight = Math.max(1, columnCount + pkFkCount);
            weights[i] = weight;
            totalWeight += weight;
        }

        double selectedStep = CT_TABLE_PENALTY_STEPS[0];
        int selectedUnits = Math.max(1, (int) Math.round(totalPoints / selectedStep));
        double bestDiff = Math.abs((selectedUnits * selectedStep) - totalPoints);
        for (int i = 1; i < CT_TABLE_PENALTY_STEPS.length; i++) {
            double step = CT_TABLE_PENALTY_STEPS[i];
            int units = Math.max(1, (int) Math.round(totalPoints / step));
            double diff = Math.abs((units * step) - totalPoints);
            if (diff + 1e-9 < bestDiff) {
                bestDiff = diff;
                selectedStep = step;
                selectedUnits = units;
            }
        }

        int[] units = new int[tableCount];
        int remainingUnits = selectedUnits;
        if (selectedUnits >= tableCount) {
            for (int i = 0; i < tableCount; i++) {
                units[i] = 1;
                remainingUnits--;
            }
        }

        if (remainingUnits > 0) {
            double[] rawExtras = new double[tableCount];
            double[] remainders = new double[tableCount];
            int distributed = 0;

            for (int i = 0; i < tableCount; i++) {
                rawExtras[i] = ((double) remainingUnits * weights[i]) / Math.max(1, totalWeight);
                int extraUnits = (int) Math.floor(rawExtras[i]);
                units[i] += extraUnits;
                distributed += extraUnits;
                remainders[i] = rawExtras[i] - extraUnits;
            }

            int left = remainingUnits - distributed;
            while (left > 0) {
                int bestIndex = 0;
                for (int i = 1; i < tableCount; i++) {
                    if (remainders[i] > remainders[bestIndex] + 1e-9
                            || (approximatelyEqual(remainders[i], remainders[bestIndex]) && weights[i] > weights[bestIndex])) {
                        bestIndex = i;
                    }
                }
                units[bestIndex]++;
                remainders[bestIndex] = -1.0d;
                left--;
            }
        }

        for (int i = 0; i < tableCount; i++) {
            penalties[i] = units[i] * selectedStep;
        }
        return penalties;
    }

    private ObjectNode ensureObject(ObjectNode parent, String fieldName) {
        JsonNode existing = parent.get(fieldName);
        if (existing instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode created = objectMapper.createObjectNode();
        parent.set(fieldName, created);
        return created;
    }

    private ArrayNode ensureArray(ObjectNode parent, String fieldName) {
        JsonNode existing = parent.get(fieldName);
        if (existing instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        ArrayNode created = objectMapper.createArrayNode();
        parent.set(fieldName, created);
        return created;
    }

    private JsonNode findRubricTable(JsonNode rubricTables, String expectedTableName) {
        for (JsonNode rubricTable : rubricTables) {
            if (normalizeIdentifier(rubricTable.path("expected_name").asText(""))
                    .equals(normalizeIdentifier(expectedTableName))) {
                return rubricTable;
            }
        }
        return null;
    }

    private boolean rubricHasColumn(JsonNode rubricColumns, String expectedColumn) {
        for (JsonNode rubricColumn : rubricColumns) {
            if (normalizeIdentifier(rubricColumn.path("name").asText(""))
                    .equals(normalizeIdentifier(expectedColumn))) {
                return true;
            }
        }
        return false;
    }

    private boolean rubricHasConstraint(
            JsonNode rubricConstraints,
            String expectedType,
            List<String> expectedColumns,
            String expectedReferencesTable,
            List<String> expectedReferencesColumns) {
        if (!rubricConstraints.isArray()) {
            return false;
        }

        for (JsonNode rubricConstraint : rubricConstraints) {
            if (!expectedType.equalsIgnoreCase(rubricConstraint.path("type").asText(""))) {
                continue;
            }

            if (!sameIdentifierList(rubricConstraint.path("columns"), expectedColumns)) {
                continue;
            }

            if ("FOREIGN_KEY".equalsIgnoreCase(expectedType)) {
                if (!normalizeIdentifier(rubricConstraint.path("references_table").asText(""))
                        .equals(normalizeIdentifier(expectedReferencesTable))) {
                    continue;
                }

                if (expectedReferencesColumns != null
                        && !expectedReferencesColumns.isEmpty()
                        && !sameIdentifierList(rubricConstraint.path("references_columns"), expectedReferencesColumns)) {
                    continue;
                }
            }

            return true;
        }

        return false;
    }

    private boolean sameIdentifierList(JsonNode actualNode, List<String> expectedValues) {
        if (!actualNode.isArray() || actualNode.size() != expectedValues.size()) {
            return false;
        }

        for (int i = 0; i < expectedValues.size(); i++) {
            if (!normalizeIdentifier(actualNode.get(i).asText(""))
                    .equals(normalizeIdentifier(expectedValues.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private Map<String, ParsedCreateTable> parseCreateTableSql(String sql) {
        Map<String, ParsedCreateTable> tables = new LinkedHashMap<>();
        if (sql == null || sql.isBlank()) {
            return tables;
        }

        Matcher matcher = CREATE_TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String tableName = normalizeIdentifier(matcher.group(1));
            int openParenIndex = matcher.end() - 1;
            int closeParenIndex = findMatchingParen(sql, openParenIndex);
            if (closeParenIndex <= openParenIndex) {
                continue;
            }

            String body = sql.substring(openParenIndex + 1, closeParenIndex);
            List<String> columns = new ArrayList<>();
            List<List<String>> primaryKeys = new ArrayList<>();
            List<ParsedForeignKey> foreignKeys = new ArrayList<>();

            for (String rawSegment : splitTopLevelComma(body)) {
                String segment = rawSegment.trim();
                if (segment.isBlank()) {
                    continue;
                }

                String withoutConstraintPrefix = CONSTRAINT_PREFIX_PATTERN.matcher(segment).replaceFirst("").trim();
                String upper = withoutConstraintPrefix.toUpperCase(Locale.ROOT);

                if (upper.startsWith("PRIMARY KEY")) {
                    List<String> pkColumns = extractColumnListFromSegment(withoutConstraintPrefix);
                    if (!pkColumns.isEmpty()) {
                        primaryKeys.add(pkColumns);
                    }
                    continue;
                }

                if (upper.startsWith("FOREIGN KEY")) {
                    List<String> fkColumns = extractColumnListFromSegment(withoutConstraintPrefix);
                    ParsedForeignKey fk = extractForeignKey(withoutConstraintPrefix, fkColumns);
                    if (fk != null) {
                        foreignKeys.add(fk);
                    }
                    continue;
                }

                String columnName = extractLeadingIdentifier(segment);
                if (columnName == null || columnName.isBlank()) {
                    continue;
                }

                columns.add(columnName);

                String upperColumn = segment.toUpperCase(Locale.ROOT);
                if (upperColumn.contains("PRIMARY KEY")) {
                    primaryKeys.add(List.of(columnName));
                }
                if (upperColumn.contains("REFERENCES")) {
                    ParsedForeignKey fk = extractForeignKey(segment, List.of(columnName));
                    if (fk != null) {
                        foreignKeys.add(fk);
                    }
                }
            }

            tables.put(tableName, new ParsedCreateTable(tableName, columns, primaryKeys, foreignKeys));
        }

        return tables;
    }

    private ParsedForeignKey extractForeignKey(String segment, List<String> columns) {
        Matcher matcher = REFERENCES_PATTERN.matcher(segment);
        if (!matcher.find()) {
            return null;
        }

        return new ParsedForeignKey(
                columns,
                normalizeIdentifier(matcher.group(1)),
                splitIdentifiers(matcher.group(2)));
    }

    private List<String> extractColumnListFromSegment(String segment) {
        int openParenIndex = segment.indexOf('(');
        if (openParenIndex < 0) {
            return List.of();
        }

        int closeParenIndex = findMatchingParen(segment, openParenIndex);
        if (closeParenIndex <= openParenIndex) {
            return List.of();
        }

        return splitIdentifiers(segment.substring(openParenIndex + 1, closeParenIndex));
    }

    private List<String> splitIdentifiers(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }

        for (String value : csv.split(",")) {
            String normalized = normalizeIdentifier(value);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }

        return result;
    }

    private List<String> splitTopLevelComma(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            }

            if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts;
    }

    private int findMatchingParen(String text, int openParenIndex) {
        int depth = 0;
        for (int i = openParenIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String extractLeadingIdentifier(String segment) {
        String trimmed = segment.trim();
        if (trimmed.isBlank()) {
            return null;
        }

        StringBuilder token = new StringBuilder();
        boolean inBracket = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '[') {
                inBracket = true;
            }
            if (!inBracket && Character.isWhitespace(ch)) {
                break;
            }
            token.append(ch);
            if (ch == ']') {
                inBracket = false;
            }
        }
        return normalizeIdentifier(token.toString());
    }

    private String normalizeIdentifier(String raw) {
        if (raw == null) {
            return "";
        }

        String normalized = raw.trim();
        if (normalized.endsWith(",")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        normalized = normalized
                .replace("[", "")
                .replace("]", "")
                .replace("`", "")
                .replace("\"", "");
        if (normalized.contains(".")) {
            String[] parts = normalized.split("\\.");
            normalized = parts[parts.length - 1];
        }
        return normalized.trim();
    }

    private record ParsedCreateTable(
            String tableName,
            List<String> columns,
            List<List<String>> primaryKeys,
            List<ParsedForeignKey> foreignKeys) {
    }

    private record ParsedForeignKey(
            List<String> columns,
            String referencesTable,
            List<String> referencesColumns) {
    }

    private String normalizeInsertRubricSchema(String rubricJson, double totalPoints) throws Exception {
        JsonNode parsed = objectMapper.readTree(rubricJson);
        if (!(parsed instanceof ObjectNode root)) {
            return rubricJson;
        }

        root.put("question_category", "INSERT_DATA");
        root.put("total_points", totalPoints);

        ObjectNode payload = ensureObject(root, "grading_payload");

        ArrayNode normalizedRules = normalizeInsertRules(extractInsertRulesNode(root, payload));
        ArrayNode normalizedTables = normalizeInsertTables(extractInsertTablesNode(root, payload));

        payload.set("grading_rules", normalizedRules.deepCopy());
        payload.set("tables", normalizedTables.deepCopy());
        payload.remove("expected_datasets");

        root.set("grading_rules", normalizedRules);
        root.set("tables", normalizedTables.deepCopy());
        root.remove("expected_datasets");

        return objectMapper.writeValueAsString(root);
    }

    private JsonNode extractInsertRulesNode(ObjectNode root, ObjectNode payload) {
        JsonNode rootRules = root.get("grading_rules");
        if (rootRules != null && rootRules.isArray()) {
            return rootRules;
        }

        JsonNode payloadRules = payload.get("grading_rules");
        if (payloadRules != null && payloadRules.isArray()) {
            return payloadRules;
        }

        return objectMapper.createArrayNode();
    }

    private JsonNode extractInsertTablesNode(ObjectNode root, ObjectNode payload) {
        JsonNode rootTables = root.get("tables");
        if (rootTables != null && rootTables.isArray()) {
            return rootTables;
        }

        JsonNode payloadTables = payload.get("tables");
        if (payloadTables != null && payloadTables.isArray()) {
            return payloadTables;
        }

        JsonNode payloadLegacy = payload.get("expected_datasets");
        if (payloadLegacy != null && payloadLegacy.isArray()) {
            return payloadLegacy;
        }

        JsonNode rootLegacy = root.get("expected_datasets");
        if (rootLegacy != null && rootLegacy.isArray()) {
            return rootLegacy;
        }

        return objectMapper.createArrayNode();
    }

    private ArrayNode normalizeInsertRules(JsonNode sourceRules) {
        ArrayNode normalized = objectMapper.createArrayNode();
        if (!sourceRules.isArray()) {
            return normalized;
        }

        int ruleIndex = 1;
        for (JsonNode ruleNode : sourceRules) {
            if (!(ruleNode instanceof ObjectNode sourceRule)) {
                continue;
            }

            ObjectNode rule = sourceRule.deepCopy();
            String ruleName = rule.path("rule_name").asText("").trim();
            if (ruleName.isBlank()) {
                ruleName = rule.path("rule_id").asText("").trim();
            }
            if (ruleName.isBlank()) {
                ruleName = String.format(Locale.ROOT, "RULE_%02d", ruleIndex);
            }
            rule.put("rule_name", ruleName);

            if (!rule.path("modifiers").isArray()) {
                rule.set("modifiers", objectMapper.createArrayNode());
            }

            normalized.add(rule);
            ruleIndex++;
        }

        return normalized;
    }

    private ArrayNode normalizeInsertTables(JsonNode sourceTables) {
        ArrayNode normalized = objectMapper.createArrayNode();
        if (!sourceTables.isArray()) {
            return normalized;
        }

        for (JsonNode tableNode : sourceTables) {
            if (!(tableNode instanceof ObjectNode sourceTable)) {
                continue;
            }

            ObjectNode table = sourceTable.deepCopy();

            String tableName = table.path("table_name").asText("").trim();
            if (tableName.isBlank()) {
                String fallback = table.path("expected_name").asText("").trim();
                if (!fallback.isBlank()) {
                    table.put("table_name", fallback);
                }
            }

            JsonNode expectedData = table.path("expected_data");
            if (!expectedData.isArray()) {
                JsonNode legacyRows = table.path("rows");
                if (legacyRows.isArray()) {
                    table.set("expected_data", legacyRows.deepCopy());
                }
            }

            JsonNode columnsConfig = table.path("columns_config");
            if (!columnsConfig.isArray()) {
                JsonNode columnsToGrade = table.path("columns_to_grade");
                if (columnsToGrade.isArray()) {
                    table.set(
                            "columns_config",
                            buildColumnsConfigFromLegacy(columnsToGrade, table.path("primary_keys")));
                }
            }

            JsonNode rowPenaltyNode = table.get("missing_row_penalty");
            if (rowPenaltyNode == null || rowPenaltyNode.isNull() || rowPenaltyNode.isMissingNode()) {
                double pointsPerRow = table.path("points_per_row").asDouble(0d);
                if (pointsPerRow > 0d) {
                    table.put("missing_row_penalty", pointsPerRow);
                }
            }

            table.remove("rows");
            table.remove("points_per_row");
            table.remove("columns_to_grade");
            table.remove("primary_keys");

            normalized.add(table);
        }

        return normalized;
    }

    private ArrayNode buildColumnsConfigFromLegacy(JsonNode columnsToGrade, JsonNode primaryKeysNode) {
        ArrayNode columnsConfig = objectMapper.createArrayNode();
        Set<String> primaryKeys = new HashSet<>();

        if (primaryKeysNode.isArray()) {
            for (JsonNode pkNode : primaryKeysNode) {
                String pk = pkNode.asText("").trim();
                if (!pk.isBlank()) {
                    primaryKeys.add(pk.toLowerCase(Locale.ROOT));
                }
            }
        }

        for (JsonNode columnNode : columnsToGrade) {
            String columnName = columnNode.asText("").trim();
            if (columnName.isBlank()) {
                continue;
            }

            ObjectNode mapped = objectMapper.createObjectNode();
            mapped.put("name", columnName);
            mapped.put("is_primary_key", primaryKeys.contains(columnName.toLowerCase(Locale.ROOT)));
            mapped.put("is_graded", true);
            mapped.put("match_type", "EXACT");
            columnsConfig.add(mapped);
        }

        return columnsConfig;
    }

    private String buildCreateTableRubricPrompt(String correctQuery, String questionContent, double totalPoints) {
        return String.format(createTableRubricPromptTemplate,
                questionContent != null ? questionContent : "Không có nội dung câu hỏi",
                correctQuery,
                totalPoints,
                totalPoints);
    }

    private String buildCreateTableRulesPrompt(String correctQuery, String questionContent, double totalPoints) {
        return String.format(createTableRulesPromptTemplate,
                questionContent != null ? questionContent : "Không có nội dung câu hỏi",
                correctQuery,
                totalPoints,
                totalPoints);
    }

    private String buildInsertRubricPrompt(String correctQuery, String questionContent, double totalPoints) {
        return String.format(insertDataRubricPromptTemplate,
                questionContent != null ? questionContent : "Không có nội dung câu hỏi",
                correctQuery,
                totalPoints,
                totalPoints,
                totalPoints);
    }

        private String buildSelectRubricPrompt(
          String correctQuery,
          String questionContent,
          double totalPoints,
          String priorQuestionContext) {
        return String.format(selectQueryRubricPromptTemplate,
                questionContent != null ? questionContent : "Không có nội dung câu hỏi",
                correctQuery,
                priorQuestionContext != null && !priorQuestionContext.isBlank()
                  ? priorQuestionContext
                  : "Không có ngữ cảnh bổ sung từ câu trước",
                totalPoints,
                totalPoints);
    }

}
