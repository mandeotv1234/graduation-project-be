package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SqlExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.Collections;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GeminiServiceImpl implements AIService {
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("\"retryDelay\"\\s*:\\s*\"([^\"]+)\"");

    private static final String GEMINI_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?is)create\\s+table\\s+([\\[\\]A-Za-z0-9_\\.]+)\\s*\\(");
    private static final Pattern CONSTRAINT_PREFIX_PATTERN = Pattern.compile(
            "(?is)^constraint\\s+[^\\s]+\\s+");
    private static final Pattern REFERENCES_PATTERN = Pattern.compile(
            "(?is)references\\s+([\\[\\]A-Za-z0-9_\\.]+)\\s*\\(([^\\)]*)\\)");
    private static final Pattern ROUTINE_SQL_OBJECT_REFERENCE_PATTERN = Pattern.compile(
            "(?is)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+((?:\\[[^\\]]+\\]|\\{SCHEMA\\}|[#@]?[A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\.\\s*(?:\\[[^\\]]+\\]|[A-Za-z_][A-Za-z0-9_]*))?)");
    private static final Pattern ROUTINE_SQL_EXEC_REFERENCE_PATTERN = Pattern.compile(
            "(?is)\\bEXEC(?:UTE)?\\s+(?:@\\w+\\s*=\\s*)?((?:\\[[^\\]]+\\]|\\{SCHEMA\\}|[#@]?[A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\.\\s*(?:\\[[^\\]]+\\]|[A-Za-z_][A-Za-z0-9_]*))?)");
    private static final Pattern ROUTINE_SCHEMA_QUALIFIED_INSERT_WITH_COLUMNS_PATTERN = Pattern.compile(
            "(?is)\\bINSERT\\s+INTO\\s+(?:\\[?\\{SCHEMA\\}\\]?|\\[[^\\]]+\\]|\\{SCHEMA\\})\\s*\\.\\s*\\[?([A-Za-z_][A-Za-z0-9_]*)\\]?\\s*\\(([^\\)]*)\\)");
    private static final Pattern ALTER_TABLE_FOREIGN_KEY_PATTERN = Pattern.compile(
            "(?is)\\bALTER\\s+TABLE\\s+([\\[\\]A-Za-z0-9_\\.]+)\\s+ADD\\s+(?:CONSTRAINT\\s+[\\[\\]A-Za-z0-9_]+\\s+)?FOREIGN\\s+KEY\\s*\\(([^\\)]*)\\)\\s+REFERENCES\\s+([\\[\\]A-Za-z0-9_\\.]+)\\s*\\(([^\\)]*)\\)");
    private static final double CT_MISSING_COLUMN_PENALTY = 0.5d;
    private static final double CT_TYPE_MISMATCH_PENALTY = 0.25d;
    private static final double CT_MISSING_PK_FK_PENALTY = 0.5d;
    private static final double[] CT_TABLE_PENALTY_STEPS = new double[] { 0.25d, 0.2d };

    private final String apiKey;
    private final String geminiModel;
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExamSchemaService examSchemaService;

    @Value("classpath:prompts/system_prompt.txt")
    private Resource systemPromptResource;

    @Value("classpath:prompts/create_table_rubric_prompt.txt")
    private Resource createTableRubricPromptResource;

    @Value("classpath:prompts/insert_data_rubric_prompt.txt")
    private Resource insertDataRubricPromptResource;

    @Value("classpath:prompts/select_query_rubric_prompt.txt")
    private Resource selectQueryRubricPromptResource;

    @Value("classpath:prompts/function_rubric_prompt.txt")
    private Resource functionRubricPromptResource;

    @Value("classpath:prompts/stored_procedure_rubric_prompt.txt")
    private Resource storedProcedureRubricPromptResource;

    @Value("classpath:prompts/stored_procedure_rubric_repair_prompt.txt")
    private Resource storedProcedureRubricRepairPromptResource;

    @Value("classpath:prompts/trigger_rubric_prompt.txt")
    private Resource triggerRubricPromptResource;

    @Value("classpath:prompts/specification_schema_prompt.txt")
    private Resource specificationSchemaPromptResource;

    @Value("classpath:prompts/create_table_rules_prompt.txt")
    private Resource createTableRulesPromptResource;

    @Value("classpath:prompts/entity-description-prompt.txt")
    private Resource entityDescriptionPromptResource;

    @Value("classpath:prompts/extract_questions_from_pdf_prompt.txt")
    private Resource extractQuestionsFromPdfPromptResource;

    private String systemPromptTemplate;
    private String createTableRubricPromptTemplate;
    private String insertDataRubricPromptTemplate;
    private String selectQueryRubricPromptTemplate;
    private String functionRubricPromptTemplate;
    private String storedProcedureRubricPromptTemplate;
    private String storedProcedureRubricRepairPromptTemplate;
    private String triggerRubricPromptTemplate;
    private String specificationSchemaPromptTemplate;
    private String createTableRulesPromptTemplate;
    private String entityDescriptionPromptTemplate;
    private String extractQuestionsFromPdfPromptTemplate;

    public GeminiServiceImpl(
            @Value("${spring.application.gemini.api-key}") String apiKey,
            @Value("${spring.application.gemini.model:gemini-2.5-flash}") String geminiModel,
            ExamSchemaService examSchemaService) {
        this.apiKey = apiKey;
        this.geminiModel = geminiModel;
        this.httpClient = null;
        this.objectMapper = new ObjectMapper();
        this.examSchemaService = examSchemaService;
    }

    private String geminiEndpoint() {
        return String.format(GEMINI_URL_TEMPLATE, geminiModel, apiKey);
    }

    @PostConstruct
    public void init() {
        try {
            this.systemPromptTemplate = StreamUtils.copyToString(systemPromptResource.getInputStream(),
                    StandardCharsets.UTF_8);
            this.createTableRubricPromptTemplate = StreamUtils
                    .copyToString(createTableRubricPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.insertDataRubricPromptTemplate = StreamUtils
                    .copyToString(insertDataRubricPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.selectQueryRubricPromptTemplate = StreamUtils
                    .copyToString(selectQueryRubricPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.functionRubricPromptTemplate = StreamUtils.copyToString(functionRubricPromptResource.getInputStream(),
                    StandardCharsets.UTF_8);
            this.storedProcedureRubricPromptTemplate = StreamUtils
                    .copyToString(storedProcedureRubricPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.storedProcedureRubricRepairPromptTemplate = StreamUtils
                    .copyToString(storedProcedureRubricRepairPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.triggerRubricPromptTemplate = StreamUtils.copyToString(triggerRubricPromptResource.getInputStream(),
                    StandardCharsets.UTF_8);
            this.specificationSchemaPromptTemplate = StreamUtils
                    .copyToString(specificationSchemaPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.createTableRulesPromptTemplate = StreamUtils
                    .copyToString(createTableRulesPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.entityDescriptionPromptTemplate = StreamUtils
                    .copyToString(entityDescriptionPromptResource.getInputStream(), StandardCharsets.UTF_8);
            this.extractQuestionsFromPdfPromptTemplate = StreamUtils
                    .copyToString(extractQuestionsFromPdfPromptResource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Không thể nạp prompt template Gemini từ resources/prompts", e);
            throw new RuntimeException("Không thể nạp prompt template Gemini", e);
        }
    }

    @Override
    public GeneratedQuestion generateSqlAnswer(String questionContent, String questionType, String schemaContext) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Thiếu Gemini API key. Bỏ qua bước sinh bằng AI.");
            return new GeneratedQuestion("-- Không thể sinh bằng AI: thiếu Gemini API key", null);
        }

        HttpClient client = getOrCreateHttpClient();
        if (client == null) {
            return new GeneratedQuestion("-- Không thể sinh bằng AI: khởi tạo HTTP client thất bại", null);
        }

        String prompt = buildPrompt(questionContent, questionType, schemaContext);
        String requestBody = buildRequestBody(prompt);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(geminiEndpoint()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Gemini API trả lỗi {}: {}", response.statusCode(), response.body());
                return new GeneratedQuestion("-- Sinh bằng AI thất bại", null);
            }

            return parseResponse(response.body());

        } catch (Exception e) {
            log.error("Không thể gọi Gemini API: {}", e.getMessage(), e);
            return new GeneratedQuestion("-- Sinh bằng AI thất bại: " + e.getMessage(), null);
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
                log.error("Không thể khởi tạo HTTP client cho Gemini: {}", e.getMessage(), e);
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
            throw new RuntimeException("Không thể tạo request body cho Gemini", e);
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
            String correctQuery = result.path("correctQuery").asText("-- AI không sinh câu truy vấn");
            String verifyScript = result.path("verifyScript").asText(null);

            return new GeneratedQuestion(correctQuery, verifyScript.isBlank() ? null : verifyScript);

        } catch (Exception e) {
            log.error("Không thể phân tích phản hồi Gemini: {}", e.getMessage());
            return new GeneratedQuestion("-- Không thể phân tích phản hồi AI", null);
        }
    }

    @Override
    public String generateGradingRubric(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String questionType,
            String priorQuestionContext,
            String schemaContext) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Thiếu Gemini API key. Không thể sinh rubric.");
            return null;
        }

        HttpClient client = getOrCreateHttpClient();
        if (client == null)
            return null;

        String basePrompt;
        if ("CREATE_TABLE_RULES".equalsIgnoreCase(questionType)) {
            basePrompt = buildCreateTableRulesPrompt(correctQuery, questionContent, totalPoints);
        } else if ("INSERT_DATA".equalsIgnoreCase(questionType)) {
            basePrompt = buildInsertRubricPrompt(correctQuery, questionContent, totalPoints);
        } else if ("SELECT_QUERY".equalsIgnoreCase(questionType)) {
            basePrompt = buildSelectRubricPrompt(correctQuery, questionContent, totalPoints, priorQuestionContext);
        } else if ("FUNCTION".equalsIgnoreCase(questionType)) {
            basePrompt = buildFunctionRubricPrompt(correctQuery, questionContent, totalPoints, questionType,
                    schemaContext);
        } else if ("STORED_PROCEDURE".equalsIgnoreCase(questionType)) {
            basePrompt = buildStoredProcedureRubricPrompt(correctQuery, questionContent, totalPoints, questionType,
                    schemaContext);
        } else if ("TRIGGER".equalsIgnoreCase(questionType)) {
            basePrompt = buildTriggerRubricPrompt(correctQuery, questionContent, totalPoints, schemaContext);
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
                        log.warn("Rubric CREATE_TABLE vẫn còn lỗi cấu trúc sau khi thử lại: {}", issues);
                        logGeneratedRubric(questionType, latestJson);
                        return null;
                    }

                    prompt = basePrompt + "\n\n=== CÁC LỖI BẮT BUỘC PHẢI SỬA ===\n"
                            + String.join("\n", issues)
                            + "\nChỉ trả về JSON đã sửa. Không bỏ sót bảng, cột, PRIMARY_KEY hoặc FOREIGN_KEY nào xuất hiện trong SQL đáp án mẫu.";
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

            if ("STORED_PROCEDURE".equalsIgnoreCase(questionType)) {
                String rubricJson = callGeminiForJson(client, basePrompt);
                if (rubricJson == null) {
                    return null;
                }

                List<RoutineRubricIssue> issues = validateStoredProcedureRubricExecutable(
                        rubricJson, correctQuery, questionContent, schemaContext);
                if (issues.isEmpty()) {
                    logGeneratedRubric(questionType, rubricJson);
                    logRoutineRubricDiagnostics(questionType, rubricJson);
                    return rubricJson;
                }

                log.warn("Rubric STORED_PROCEDURE chạy kiểm tra thất bại, thử sửa một lần: {}", issues);
                String repairPrompt = buildStoredProcedureRubricRepairPrompt(
                        questionContent, correctQuery, schemaContext, rubricJson, issues);
                String repairedRubricJson = callGeminiForJson(client, repairPrompt);
                if (repairedRubricJson == null) {
                    return buildNeedsReviewRubricResponse(rubricJson, issues);
                }

                List<RoutineRubricIssue> repairedIssues = validateStoredProcedureRubricExecutable(
                        repairedRubricJson, correctQuery, questionContent, schemaContext);
                if (repairedIssues.isEmpty()) {
                    logGeneratedRubric(questionType, repairedRubricJson);
                    logRoutineRubricDiagnostics(questionType, repairedRubricJson);
                    return repairedRubricJson;
                }

                log.warn("Rubric STORED_PROCEDURE vẫn cần kiểm tra thủ công sau một lần sửa: {}", repairedIssues);
                return buildNeedsReviewRubricResponse(repairedRubricJson, repairedIssues);
            }

            if ("FUNCTION".equalsIgnoreCase(questionType)) {
                String prompt = basePrompt;
                String latestJson = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    latestJson = callGeminiForJson(client, prompt);
                    if (latestJson == null) {
                        return null;
                    }

                    JsonNode rubricNode = objectMapper.readTree(latestJson);
                    List<String> issues = validateRoutineRubricHeuristics(questionType, rubricNode, schemaContext);
                    if (issues.isEmpty()) {
                        logGeneratedRubric(questionType, latestJson);
                        logRoutineRubricDiagnostics(questionType, latestJson);
                        return latestJson;
                    }

                    if (attempt == 1) {
                        log.warn("Rubric ROUTINE vẫn còn lỗi sau khi thử lại: {}", issues);
                        logGeneratedRubric(questionType, latestJson);
                        logRoutineRubricDiagnostics(questionType, latestJson);
                        List<RoutineRubricIssue> rubricIssues = issues.stream()
                                .map(msg -> new RoutineRubricIssue("RUBRIC", "VALIDATION", "HEURISTIC_ISSUE", msg, null))
                                .collect(java.util.stream.Collectors.toList());
                        return buildNeedsReviewRubricResponse(latestJson, rubricIssues);
                    }

                    prompt = basePrompt + "\n\n=== CÁC LỖI BẮT BUỘC PHẢI SỬA CHO RUBRIC ROUTINE ===\n"
                            + String.join("\n", issues)
                            + "\nChỉ trả về JSON đã sửa. Không dùng markdown.";
                }
                return latestJson;
            }

            if ("TRIGGER".equalsIgnoreCase(questionType)) {
                String prompt = basePrompt;
                String latestJson = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    latestJson = callGeminiForJson(client, prompt);
                    if (latestJson == null) {
                        return null;
                    }

                    JsonNode rubricNode = objectMapper.readTree(latestJson);
                    List<String> issues = validateTriggerRubricHeuristics(rubricNode, schemaContext);
                    if (issues.isEmpty()) {
                        String wrappedRubric = wrapTriggerRubric(latestJson, totalPoints);
                        logGeneratedRubric(questionType, wrappedRubric);
                        return wrappedRubric;
                    }

                    if (attempt == 2) {
                        log.warn("Rubric TRIGGER vẫn còn lỗi sau khi thử lại: {}", issues);
                        String wrappedRubric = wrapTriggerRubric(latestJson, totalPoints);
                        logGeneratedRubric(questionType, wrappedRubric);
                        return wrappedRubric;
                    }

                    prompt = basePrompt + "\n\n=== CÁC LỖI BẮT BUỘC PHẢI SỬA CHO RUBRIC TRIGGER ===\n"
                            + String.join("\n", issues)
                            + "\nChỉ trả về JSON đã sửa. Không dùng markdown. Đọc kỹ DDL schema context để dùng đúng tên cột và kiểu dữ liệu.";
                }
                String wrappedRubric = wrapTriggerRubric(latestJson, totalPoints);
                logGeneratedRubric(questionType, wrappedRubric);
                return wrappedRubric;
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
                    log.warn("Rubric SELECT vẫn còn lỗi kiểm tra tự động sau khi thử lại: {}", issues);
                    logGeneratedRubric(questionType, latestJson);
                    return latestJson;
                }

                prompt = basePrompt + "\n\n=== FEEDBACK BẮT BUỘC SỬA ===\n"
                        + String.join("\n", issues)
                        + "\nHãy trả về JSON mới hoàn chỉnh, chỉ JSON, không giải thích.";
            }

            return latestJson;

        } catch (Exception e) {
            log.error("Không thể sinh rubric chấm điểm: {}", e.getMessage(), e);
            return null;
        }
    }

    private String callGeminiForJson(HttpClient client, String prompt) throws Exception {
        String requestBody = buildRequestBody(prompt, 16384);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(geminiEndpoint()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Gemini API trả lỗi {}: {}", response.statusCode(), response.body());
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode candidate = root.path("candidates").get(0);
        String finishReason = candidate.path("finishReason").asText("");
        JsonNode usage = root.path("usageMetadata");
        String text = candidate
                .path("content")
                .path("parts").get(0)
                .path("text").asText();

        text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        log.info(
                "Phản hồi Gemini: finishReason={} promptTokens={} candidatesTokens={} thoughtsTokens={} totalTokens={} textLength={}\n--- BẮT ĐẦU RAW TEXT ---\n{}\n--- KẾT THÚC RAW TEXT ---",
                finishReason,
                usage.path("promptTokenCount").asInt(-1),
                usage.path("candidatesTokenCount").asInt(-1),
                usage.path("thoughtsTokenCount").asInt(-1),
                usage.path("totalTokenCount").asInt(-1),
                text.length(),
                text);

        try {
            objectMapper.readTree(text);
        } catch (Exception parseErr) {
            log.error(
                    "Gemini trả về JSON không hợp lệ. finishReason={} textLength={} parseError={}\nPhản hồi wrapper đầy đủ:\n{}",
                    finishReason, text.length(), parseErr.getMessage(), response.body());
            if ("MAX_TOKENS".equalsIgnoreCase(finishReason)) {
                throw new RuntimeException(
                        "Gemini bị cắt do MAX_TOKENS (output > maxOutputTokens). Tăng maxOutputTokens hoặc giảm phạm vi rubric. textLength="
                                + text.length(),
                        parseErr);
            }
            throw parseErr;
        }
        return text;
    }

    private void logGeneratedRubric(String questionType, String rubricJson) {
        if (rubricJson == null || rubricJson.isBlank()) {
            log.warn("Gemini trả về rubric rỗng cho questionType={}", questionType);
            return;
        }

        try {
            JsonNode rubricNode = objectMapper.readTree(rubricJson);
            String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rubricNode);
            log.info("Gemini đã sinh rubric cho questionType={}:\n{}", questionType, prettyJson);
        } catch (Exception e) {
            log.warn("Gemini đã sinh rubric cho questionType={} nhưng không thể định dạng log đẹp. Rubric gốc: {}",
                    questionType, rubricJson);
        }
    }

    private void logRoutineRubricDiagnostics(String questionType, String rubricJson) {
        if (rubricJson == null || rubricJson.isBlank()) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(rubricJson);
            JsonNode payload = root.path("grading_payload");
            JsonNode routines = payload.path("routines");
            JsonNode testCases = payload.path("test_cases");

            log.debug("[ROUTINE_RUBRIC_RAW][{}] {}", questionType, rubricJson);
            log.info("[ROUTINE_RUBRIC_SUMMARY][{}] routines={}, testCases={}",
                    questionType,
                    routines.isArray() ? routines.size() : 0,
                    testCases.isArray() ? testCases.size() : 0);

            if (!testCases.isArray()) {
                log.warn("[ROUTINE_RUBRIC_INVALID][{}] grading_payload.test_cases bị thiếu hoặc không phải mảng",
                        questionType);
                return;
            }

            for (int i = 0; i < testCases.size(); i++) {
                JsonNode tc = testCases.get(i);
                String caseName = tc.path("case_name").asText("TC" + (i + 1));
                String setup = tc.path("setup_script").asText("");
                String invocation = tc.path("invocation_query").asText("");
                String validation = tc.path("validation_query").asText("");
                String combinedSql = setup + "\n" + invocation + "\n" + validation;

                log.info(
                        "[ROUTINE_RUBRIC_TC][{}][{}] caseName='{}', verificationType='{}', matchType='{}', scoreWeight='{}'",
                        questionType,
                        i + 1,
                        caseName,
                        tc.path("verification_type").asText(""),
                        tc.path("match_type").asText(""),
                        tc.path("score_weight").asText(""));

                if (validation.isBlank()
                        && !"PRINT_OUTPUT".equalsIgnoreCase(tc.path("verification_type").asText(""))) {
                    log.warn("[ROUTINE_RUBRIC_TC_INVALID][{}][{}] validation_query đang trống với caseName='{}'",
                            questionType, i + 1, caseName);
                }

                if (combinedSql.matches("(?is).*\\bTHIS\\s*\\..*")
                        || combinedSql.matches("(?is).*\\[\\s*THIS\\s*\\].*")
                        || combinedSql.matches("(?is).*\\bdbo\\s*\\..*")) {
                    log.warn(
                            "[ROUTINE_RUBRIC_TC_SCHEMA_WARNING][{}][{}] caseName='{}' có thể chứa giá trị thay thế schema không hợp lệ. setup='{}' invocation='{}' validation='{}'",
                            questionType,
                            i + 1,
                            caseName,
                            truncateForLog(setup, 500),
                            truncateForLog(invocation, 500),
                            truncateForLog(validation, 500));
                }
            }
        } catch (Exception e) {
            log.warn("[ROUTINE_RUBRIC_DIAGNOSTICS_FAILED][{}] Không thể ghi log chẩn đoán: {}", questionType,
                    e.getMessage());
        }
    }

    private List<String> validateRoutineRubricHeuristics(String questionType, JsonNode rubricNode,
            String schemaContext) {
        List<String> issues = new ArrayList<>();
        JsonNode testCases = rubricNode.path("grading_payload").path("test_cases");

        if (!testCases.isArray() || testCases.isEmpty()) {
            issues.add("- grading_payload.test_cases phải là mảng không rỗng.");
            return issues;
        }

        boolean storedProcedure = "STORED_PROCEDURE".equalsIgnoreCase(questionType);
        Map<String, Set<String>> identityColumnsByTable = parseIdentityColumnsByTable(schemaContext);
        List<RoutineForeignKey> foreignKeys = parseRoutineForeignKeys(schemaContext);
        boolean hasSideEffectCase = false;
        boolean hasPrintOutputCase = false;
        boolean hasSideEffectFailureCase = false;
        double totalScoreWeight = 0.0d;

        for (int i = 0; i < testCases.size(); i++) {
            JsonNode tc = testCases.get(i);
            String label = "TC" + (i + 1) + " (" + tc.path("case_name").asText("unnamed") + ")";
            String verificationType = tc.path("verification_type").asText("");
            String setup = tc.path("setup_script").asText("");
            String invocation = tc.path("invocation_query").asText("");
            String validation = tc.path("validation_query").asText("");
            String combined = setup + "\n" + invocation + "\n" + validation;
            String scenarioText = tc.path("case_name").asText("") + " " + tc.path("description").asText("");
            List<RoutineSetupInsert> setupInserts = extractSetupInserts(setup);
            Map<String, Set<String>> setupInsertColumnsByTable = extractSetupInsertColumnsByTable(setupInserts);
            boolean sideEffect = "SIDE_EFFECT".equalsIgnoreCase(verificationType);
            boolean printOutput = "PRINT_OUTPUT".equalsIgnoreCase(verificationType);

            if (tc.has("score_weight") && tc.get("score_weight").isNumber()) {
                totalScoreWeight += Math.abs(tc.get("score_weight").asDouble());
            }
            hasSideEffectCase |= sideEffect;
            hasPrintOutputCase |= printOutput;
            hasSideEffectFailureCase |= sideEffect && looksLikeFailureScenario(scenarioText);

            if (!"PRINT_OUTPUT".equalsIgnoreCase(verificationType) && validation.isBlank()) {
                issues.add("- " + label + ": validation_query là bắt buộc trừ khi verification_type là PRINT_OUTPUT.");
            }

            if ("PRINT_OUTPUT".equalsIgnoreCase(verificationType) && !validation.isBlank()) {
                issues.add("- " + label + ": PRINT_OUTPUT phải để trống validation_query. "
                        + "Engine tự bắt thông báo PRINT của SQL Server; không truy vấn PRINT_LOG.");
            }

            if (combined.matches("(?is).*\\bPRINT_LOG\\b.*")) {
                issues.add("- " + label
                        + ": không dùng PRINT_LOG. Hệ thống không cung cấp bảng này; PRINT_OUTPUT được engine bắt trực tiếp.");
            }

            if (combined.matches("(?is).*\\bdbo\\s*\\..*")
                    || combined.matches("(?is).*\\[\\s*dbo\\s*\\]\\s*\\..*")) {
                issues.add("- " + label + ": không dùng dbo.; hãy dùng [{SCHEMA}].ObjectName ở mọi nơi.");
            }

            if (combined.matches("(?is).*\\bTHIS\\s*\\..*") || combined.matches("(?is).*\\[\\s*THIS\\s*\\].*")) {
                issues.add("- " + label + ": không dùng THIS làm giá trị thay thế schema; chỉ dùng [{SCHEMA}].");
            }

            addRoutineSchemaQualificationIssues(issues, label, combined);

            if (invocation.matches("(?is).*@([A-Za-z0-9_]+)\\s*=\\s*@\\1\\b.*")
                    && !invocation.matches("(?is).*\\bDECLARE\\s+@\\w+\\b.*")) {
                issues.add("- " + label + ": invocation_query dùng biến chưa DECLARE làm giá trị tham số "
                        + "(ví dụ @MaXe = @MaXe). Hãy dùng giá trị literal trực tiếp, "
                        + "như @MaXe = 'XE001', hoặc DECLARE biến trước trong invocation_query.");
            }

            if (setup.matches("(?is).*\\b(?:CREATE|ALTER|DROP)\\s+TABLE\\b.*")) {
                issues.add("- " + label + ": setup_script không được CREATE/ALTER/DROP bảng nền. "
                        + "DDL của đặc tả đề thi đã được nạp; chỉ seed dữ liệu bằng DELETE/INSERT/UPDATE.");
            }

            if (setup.matches("(?is).*\\bINSERT\\s+INTO\\b.*")
                    && !setup.matches("(?is).*\\bDELETE\\s+FROM\\b.*")) {
                issues.add("- " + label + ": setup_script INSERT dữ liệu test nhưng chưa DELETE các khóa đó trước. "
                        + "Hãy làm setup có thể chạy lặp lại vì DDL đặc tả có thể đã có dữ liệu seed.");
            }

            addRoutineSetupIdentityIssues(issues, label, setup, setupInsertColumnsByTable, identityColumnsByTable);
            addRoutineSetupForeignKeyIssues(issues, label, setupInserts, setupInsertColumnsByTable, foreignKeys);
            addRoutineSetupForeignKeyOrderIssues(issues, label, setupInserts, foreignKeys);
            // Do not block on missing parent rows inferred only from invocation/validation.
            // Many valid failure cases intentionally pass a missing FK-like input
            // (for example CustomerId does not exist) and should be judged by the
            // executable gate against the reference procedure instead of this heuristic.

            if (setupInsertColumnsByTable.containsKey(normalizeIdentifierKey("ChuyenXe"))) {
                if (setupInsertsNonNullForeignKey(setupInserts, "ChuyenXe", List.of("TuyenXe"))
                        && !setupInsertColumnsByTable.containsKey(normalizeIdentifierKey("TuyenXe"))) {
                    issues.add("- " + label + ": setup_script INSERT dòng ChuyenXe nhưng chưa INSERT dòng cha "
                            + "TuyenXe tương ứng trước. ChuyenXe.TuyenXe có khóa ngoại tới TuyenXe.MaTuyen.");
                }
                if (setupInsertsNonNullForeignKey(setupInserts, "ChuyenXe", List.of("MaXe"))
                        && !setupInsertColumnsByTable.containsKey(normalizeIdentifierKey("Xe"))) {
                    issues.add("- " + label + ": setup_script INSERT dòng ChuyenXe nhưng chưa INSERT dòng cha "
                            + "Xe tương ứng trước. ChuyenXe.MaXe có khóa ngoại tới Xe.MaXe.");
                }
            }

            if (storedProcedure) {
                if (!"RESULT_SET".equalsIgnoreCase(verificationType)
                        && invocation.matches("(?is).*\\bSELECT\\b.*")) {
                    issues.add("- " + label + ": invocation_query không được trả result set bằng SELECT. "
                            + "Nó chỉ nên DECLARE biến và EXEC stored procedure. Chuyển SELECT @out AS ... "
                            + "hoặc SELECT @rc AS ... sang validation_query.");
                }

                if (validation.matches("(?is).*\\b(?:CROSS|OUTER)\\s+APPLY\\b.*")) {
                    issues.add("- " + label + ": validation_query không nên dùng CROSS APPLY/OUTER APPLY để kiểm tra SIDE_EFFECT. "
                            + "Cách này có thể lỗi với cột không tên và có thể trả 0 dòng khi dòng mục tiêu "
                            + "không tồn tại. Hãy dùng scalar subquery, ví dụ SELECT @Result AS return_value, "
                            + "(SELECT COUNT(*) FROM [{SCHEMA}].<table> WHERE <condition>) AS affected_count.");
                }

                if (validation.matches("(?is).*@result_table.*")
                        && !(invocation + "\n" + validation)
                                .matches("(?is).*DECLARE\\s+@result_table\\s+TABLE\\s*\\(.*")) {
                    issues.add("- " + label + ": validation_query tham chiếu @result_table nhưng test case chưa "
                            + "DECLARE nó. Nếu dùng @result_table, hãy đặt DECLARE @result_table TABLE (...) và INSERT EXEC "
                            + "trong invocation_query trước khi validation_query SELECT từ biến bảng này.");
                }

                if (validation.matches("(?is).*SELECT\\s+return_value\\s+FROM\\s+@\\w+.*")) {
                    issues.add("- " + label + ": validation_query không hợp lệ. @rc là biến scalar, không phải bảng. "
                            + "Dùng SELECT @rc AS return_value, không dùng SELECT return_value FROM @rc.");
                }

                if (validation.matches("(?is).*SELECT\\s+RETURN_VALUE\\s+FROM\\s+.*")) {
                    issues.add("- " + label + ": validation_query cho stored procedure SQL Server không hợp lệ. "
                            + "Không dùng SELECT RETURN_VALUE FROM <stored_procedure>. "
                            + "Hãy dùng DECLARE @rc INT; EXEC @rc = [{SCHEMA}].<sp> ...; SELECT @rc AS return_value, "
                            + "hoặc dùng validation_query SIDE_EFFECT SELECT từ các bảng bị tác động.");
                }

                if ("RETURN_VALUE".equalsIgnoreCase(verificationType)
                        && !invocation.matches("(?is).*EXEC\\s+@\\w+\\s*=.*")
                        && !invocation.matches("(?is).*SELECT\\s+@\\w+\\s+AS\\s+return_value.*")) {
                    issues.add("- " + label
                            + ": STORED_PROCEDURE RETURN_VALUE phải bắt return code trong invocation_query, "
                            + "ví dụ DECLARE @rc INT; EXEC @rc = [{SCHEMA}].<sp> ...; SELECT @rc AS return_value.");
                }

                if (sideEffect
                        && looksLikeSuccessScenario(scenarioText)
                        && !looksLikeDeleteScenario(scenarioText)
                        && sideEffectValidationOnlyCountsRows(validation)) {
                    issues.add("- " + label + ": SIDE_EFFECT thành công của DML INSERT/UPDATE phải SELECT các cột nghiệp vụ "
                            + "bị tác động, không chỉ COUNT(*). Nên có @rc AS return_value cùng các cột như khóa, "
                            + "khóa ngoại, và giá trị input/đã cập nhật.");
                }
            }
        }

        if (Math.abs(totalScoreWeight - 1.0d) > 0.01d) {
            issues.add("- Tổng score_weight của test_cases phải bằng 1.0. Tổng hiện tại là "
                    + String.format(Locale.ROOT, "%.2f", totalScoreWeight) + ".");
        }

        if (storedProcedure && hasSideEffectCase && hasPrintOutputCase && !hasSideEffectFailureCase) {
            issues.add(
                    "- Rubric stored procedure DML có test case lỗi dạng PRINT_OUTPUT nhưng chưa có test case lỗi/không đổi dữ liệu dạng SIDE_EFFECT. "
                            + "Hãy thêm ít nhất một test SIDE_EFFECT với input không hợp lệ, bắt @rc AS return_value và chứng minh không có "
                            + "INSERT/UPDATE/DELETE ngoài ý muốn.");
        }

        return issues;
    }

    private List<RoutineRubricIssue> validateStoredProcedureRubricExecutable(
            String rubricJson,
            String correctQuery,
            String questionContent,
            String schemaContext) {
        List<RoutineRubricIssue> issues = new ArrayList<>();
        JsonNode rubricNode;
        try {
            rubricNode = objectMapper.readTree(rubricJson);
        } catch (Exception e) {
            issues.add(new RoutineRubricIssue("RUBRIC", "PARSE", "INVALID_JSON", e.getMessage(), null));
            return issues;
        }

        for (String issue : validateRoutineRubricHeuristics("STORED_PROCEDURE", rubricNode, schemaContext)) {
            issues.add(new RoutineRubricIssue("RUBRIC", "STATIC_VALIDATION", "HEURISTIC_ISSUE", issue, null));
        }
        if (!issues.isEmpty()) {
            return issues;
        }

        String ddlScript = buildExecutableDdlFromSchemaContext(schemaContext);
        if (ddlScript == null || ddlScript.isBlank()) {
            issues.add(new RoutineRubricIssue("RUBRIC", "SCHEMA", "SCHEMA_CONTEXT_NOT_EXECUTABLE",
                    "Không thể dựng DDL thực thi từ schemaContext nên không thể chạy kiểm tra rubric SP đã sinh.",
                    null));
            return issues;
        }

        JsonNode testCases = rubricNode.path("grading_payload").path("test_cases");
        if (!testCases.isArray() || testCases.isEmpty()) {
            issues.add(new RoutineRubricIssue("RUBRIC", "STATIC_VALIDATION", "NO_TEST_CASES",
                    "grading_payload.test_cases phải là mảng không rỗng.", null));
            return issues;
        }

        String schemaName = "rubric_sp_validate_" + System.currentTimeMillis();
        try {
            examSchemaService.resetSchema(schemaName, false);
            try {
                examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, null);
            } catch (Exception e) {
                issues.add(new RoutineRubricIssue("RUBRIC", "SCHEMA", classifySqlError(e),
                        "Không thể nạp DDL từ schemaContext: " + rootMessage(e), truncateForLog(ddlScript, 1200)));
                return issues;
            }

            try {
                executeSqlScriptBatches(schemaName, correctQuery);
            } catch (Exception e) {
                issues.add(new RoutineRubricIssue("RUBRIC", "REFERENCE_SQL", classifySqlError(e),
                        "SQL đáp án chạy thất bại trên schema kiểm tra: " + rootMessage(e),
                        truncateForLog(correctQuery, 1200)));
                return issues;
            }

            Set<String> expectedRoutineNames = extractExpectedRoutineNames(rubricNode);
            for (int i = 0; i < testCases.size(); i++) {
                JsonNode tc = testCases.get(i);
                RoutineRubricIssue issue = runStoredProcedureRubricTestCase(schemaName, tc, i + 1,
                        expectedRoutineNames);
                if (issue != null) {
                    issues.add(issue);
                }
            }
        } finally {
            try {
                examSchemaService.dropSchema(schemaName);
            } catch (Exception e) {
                log.warn("Không thể xóa schema kiểm tra rubric SP {}: {}", schemaName, e.getMessage());
            }
        }

        return issues;
    }

    private RoutineRubricIssue runStoredProcedureRubricTestCase(
            String schemaName,
            JsonNode tc,
            int index,
            Set<String> expectedRoutineNames) {
        String caseId = tc.path("case_id").asText("TC_" + index);
        String setup = resolveRoutineSql(textOrNull(tc, "setup_script"), schemaName, schemaName);
        String invocation = resolveRoutineSql(textOrNull(tc, "invocation_query"), schemaName, schemaName);
        String validation = resolveRoutineSql(textOrNull(tc, "validation_query"), schemaName, schemaName);
        String verificationType = tc.path("verification_type").asText("RETURN_VALUE");
        boolean printOutput = "PRINT_OUTPUT".equalsIgnoreCase(verificationType);

        if (!printOutput && (validation == null || validation.isBlank())) {
            return new RoutineRubricIssue(caseId, "VALIDATION", "MISSING_VALIDATION_QUERY",
                    "validation_query là bắt buộc trừ khi verification_type là PRINT_OUTPUT.", null);
        }
        if (!printOutput && validationCallsExpectedRoutine(validation, expectedRoutineNames)) {
            return new RoutineRubricIssue(caseId, "VALIDATION", "VALIDATION_REEXECUTES_ROUTINE",
                    "validation_query không được EXEC/EXECUTE lại stored procedure đang kiểm tra. "
                            + "Chỉ gọi procedure đúng một lần trong invocation_query, sau đó kiểm tra output variable "
                            + "và side effect chỉ bằng SELECT.",
                    truncateForLog(validation, 1200));
        }

        StringBuilder batch = new StringBuilder();
        batch.append("DECLARE @__rubric_phase NVARCHAR(32) = N'SETUP';\n");
        batch.append("BEGIN TRY\n");
        batch.append("  BEGIN TRANSACTION;\n");
        appendSqlStatement(batch, setup);
        batch.append("  SET @__rubric_phase = N'INVOCATION';\n");
        appendSqlStatement(batch, invocation);
        if (!printOutput && validation != null && !validation.isBlank()) {
            batch.append("  SET @__rubric_phase = N'VALIDATION';\n");
            batch.append("  SELECT NULL AS __VALIDATION_MARKER__;\n");
            appendSqlStatement(batch, validation);
        }
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append("END TRY\n");
        batch.append("BEGIN CATCH\n");
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append(
                "  DECLARE @__rubric_msg NVARCHAR(4000) = CONCAT(N'RUBRIC_PHASE=', @__rubric_phase, N'; ', ERROR_MESSAGE());\n");
        batch.append("  THROW 51000, @__rubric_msg, 1;\n");
        batch.append("END CATCH;");

        try {
            SqlExecutionResult result = examSchemaService.executeSqlBatchAsSchemaUser(schemaName, batch.toString());
            if (!printOutput && (result == null || result.getResultSet() == null || result.getResultSet().isEmpty())) {
                return new RoutineRubricIssue(caseId, "VALIDATION", "VALIDATION_RETURNED_NO_ROWS",
                        "validation_query nên trả ít nhất một dòng ổn định để so sánh.",
                        truncateForLog(validation, 1200));
            }
            return null;
        } catch (Exception e) {
            String message = rootMessage(e);
            return new RoutineRubricIssue(caseId, extractRubricPhase(message), classifySqlError(e),
                    message, truncateForLog(batch.toString(), 1600));
        }
    }

    private Set<String> extractExpectedRoutineNames(JsonNode rubricNode) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode routines = rubricNode.path("grading_payload").path("routines");
        if (!routines.isArray()) {
            return names;
        }
        for (JsonNode routine : routines) {
            String name = normalizeIdentifierKey(routine.path("expected_name").asText(""));
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private boolean validationCallsExpectedRoutine(String validation, Set<String> expectedRoutineNames) {
        if (validation == null || validation.isBlank()
                || expectedRoutineNames == null || expectedRoutineNames.isEmpty()) {
            return false;
        }
        Matcher matcher = Pattern.compile("(?is)\\bEXEC(?:UTE)?\\b(?:\\s+@\\w+\\s*=)?\\s+([^\\s;,(]+)")
                .matcher(validation);
        while (matcher.find()) {
            String calledName = normalizeIdentifierKey(matcher.group(1));
            if (expectedRoutineNames.contains(calledName)) {
                return true;
            }
        }
        return false;
    }

    private String buildExecutableDdlFromSchemaContext(String schemaContext) {
        if (schemaContext == null || schemaContext.isBlank()) {
            return null;
        }
        String trimmed = schemaContext.trim();
        if (trimmed.matches("(?is).*\\bCREATE\\s+TABLE\\b.*")) {
            return trimmed;
        }
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(trimmed);
            JsonNode tables = root.isArray() ? root : firstArray(root, "tables", "databaseSpec", "schema", "items");
            if (tables == null || !tables.isArray() || tables.isEmpty()) {
                return null;
            }

            StringBuilder createTables = new StringBuilder();
            StringBuilder foreignKeys = new StringBuilder();
            for (JsonNode table : tables) {
                String tableName = firstText(table, "tableName", "table_name", "name");
                if (tableName.isBlank()) {
                    continue;
                }
                JsonNode columns = firstArray(table, "columns", "columnDefinitions", "fields");
                if (columns == null || !columns.isArray() || columns.isEmpty()) {
                    continue;
                }

                List<String> columnDefs = new ArrayList<>();
                List<String> primaryKeys = new ArrayList<>();
                for (JsonNode column : columns) {
                    String columnName = firstText(column, "columnName", "column_name", "name");
                    if (columnName.isBlank()) {
                        continue;
                    }
                    String dataType = firstText(column, "rawDataType", "raw_data_type", "dataType", "data_type",
                            "type");
                    if (dataType.isBlank()) {
                        dataType = "NVARCHAR(255)";
                    }
                    boolean nullable = column.path("nullable").asBoolean(column.path("isNullable").asBoolean(true));
                    boolean primaryKey = column.path("primaryKey").asBoolean(false)
                            || column.path("isPrimaryKey").asBoolean(false)
                            || column.path("primary_key").asBoolean(false);
                    if (primaryKey) {
                        primaryKeys.add(quoteSqlIdentifier(columnName));
                        nullable = false;
                    }
                    columnDefs
                            .add(quoteSqlIdentifier(columnName) + " " + dataType + (nullable ? " NULL" : " NOT NULL"));

                    String referencesTable = firstText(column,
                            "referencesTable", "references_table", "referencedTable", "referenced_table");
                    String referencesColumn = firstText(column,
                            "referencesColumn", "references_column", "referencedColumn", "referenced_column");
                    if (!referencesTable.isBlank()) {
                        if (referencesColumn.isBlank()) {
                            referencesColumn = "id";
                        }
                        String constraintName = "FK_" + normalizeIdentifier(tableName) + "_"
                                + normalizeIdentifier(columnName) + "_" + normalizeIdentifier(referencesTable);
                        foreignKeys.append("ALTER TABLE ").append(quoteSqlIdentifier(tableName))
                                .append(" ADD CONSTRAINT ").append(quoteSqlIdentifier(constraintName))
                                .append(" FOREIGN KEY (").append(quoteSqlIdentifier(columnName)).append(")")
                                .append(" REFERENCES ").append(quoteSqlIdentifier(referencesTable))
                                .append("(").append(quoteSqlIdentifier(referencesColumn)).append(");\n");
                    }
                }
                if (columnDefs.isEmpty()) {
                    continue;
                }
                if (!primaryKeys.isEmpty()) {
                    columnDefs.add("CONSTRAINT " + quoteSqlIdentifier("PK_" + normalizeIdentifier(tableName))
                            + " PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
                }
                createTables.append("CREATE TABLE ").append(quoteSqlIdentifier(tableName)).append(" (\n  ")
                        .append(String.join(",\n  ", columnDefs))
                        .append("\n);\n");
            }

            String ddl = createTables.append(foreignKeys).toString().trim();
            return ddl.isBlank() ? null : ddl;
        } catch (Exception e) {
            log.warn("Không thể dựng DDL thực thi từ schemaContext: {}", e.getMessage());
            return null;
        }
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

    private List<String> splitBatchBeforeCreateRoutine(String batch) {
        if (batch == null || batch.isBlank()) {
            return List.of();
        }

        Matcher matcher = Pattern
                .compile("(?is)\\bCREATE\\s+(?:OR\\s+ALTER\\s+)?(?:PROCEDURE|PROC|FUNCTION|TRIGGER)\\b")
                .matcher(batch);
        if (!matcher.find()) {
            return List.of(batch);
        }

        String prefix = batch.substring(0, matcher.start()).trim();
        String routine = batch.substring(matcher.start()).trim();
        return prefix.isBlank() ? List.of(routine) : List.of(prefix, routine);
    }

    private String normalizeDboReferences(String sql, String schemaName) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        return sql.replaceAll("(?i)\\bdbo\\s*\\.", "[" + schemaName + "].");
    }

    private String resolveRoutineSql(String sql, String targetSchema, String teacherSchema) {
        if (sql == null) {
            return null;
        }
        return sql
                .replace("{SCHEMA}", targetSchema)
                .replace("{TEACHER_SCHEMA}", teacherSchema)
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .trim()
                .replaceAll("(?i)\\[dbo\\]\\s*\\.", "[" + targetSchema + "].")
                .replaceAll("(?i)\\bdbo\\s*\\.", "[" + targetSchema + "].");
    }

    private String textOrNull(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.isTextual() ? value.asText() : value.toString();
        return text.isBlank() ? null : text;
    }

    private void appendSqlStatement(StringBuilder batch, String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        batch.append("  ").append(sql).append(";\n");
    }

    private String buildStoredProcedureRubricRepairPrompt(
            String questionContent,
            String correctQuery,
            String schemaContext,
            String currentRubricJson,
            List<RoutineRubricIssue> issues) throws Exception {
        return String.format(storedProcedureRubricRepairPromptTemplate,
                questionContent != null ? questionContent : "Không có nội dung câu hỏi",
                correctQuery != null ? correctQuery : "",
                schemaContext != null && !schemaContext.isBlank() ? truncateForLog(schemaContext, 8000)
                        : "Không có schema context.",
                currentRubricJson != null ? currentRubricJson : "",
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(issues));
    }

    private String buildNeedsReviewRubricResponse(String rubricJson, List<RoutineRubricIssue> issues) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("status", "NEEDS_REVIEW");
            root.set("rubric", objectMapper.readTree(rubricJson));
            root.set("issues", objectMapper.valueToTree(issues));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return rubricJson;
        }
    }

    private String quoteSqlIdentifier(String identifier) {
        String normalized = normalizeIdentifier(identifier);
        return "[" + normalized.replace("]", "]]") + "]";
    }

    private String classifySqlError(Exception e) {
        String message = rootMessage(e).toLowerCase(Locale.ROOT);
        if (message.contains("foreign key") || message.contains("conflicted with the reference constraint")) {
            return "FOREIGN_KEY_VIOLATION";
        }
        if (message.contains("invalid column name")) {
            return "INVALID_COLUMN";
        }
        if (message.contains("invalid object name")) {
            return "INVALID_OBJECT";
        }
        if (message.contains("already been declared")) {
            return "VARIABLE_REDECLARED";
        }
        if (message.contains("could not find stored procedure")) {
            return "ROUTINE_NOT_FOUND";
        }
        return "SQL_EXECUTION_ERROR";
    }

    private String extractRubricPhase(String message) {
        if (message == null) {
            return "EXECUTION";
        }
        Matcher matcher = Pattern.compile("RUBRIC_PHASE=([A-Z_]+)").matcher(message);
        return matcher.find() ? matcher.group(1) : "EXECUTION";
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private Map<String, Set<String>> parseIdentityColumnsByTable(String schemaContext) {
        Map<String, Set<String>> identityColumnsByTable = new LinkedHashMap<>();
        if (schemaContext == null || schemaContext.isBlank()) {
            return identityColumnsByTable;
        }

        Matcher matcher = CREATE_TABLE_PATTERN.matcher(schemaContext);
        while (matcher.find()) {
            String tableName = normalizeIdentifierKey(matcher.group(1));
            int openParenIndex = matcher.end() - 1;
            int closeParenIndex = findMatchingParen(schemaContext, openParenIndex);
            if (tableName.isBlank() || closeParenIndex <= openParenIndex) {
                continue;
            }

            String body = schemaContext.substring(openParenIndex + 1, closeParenIndex);
            for (String rawSegment : splitTopLevelComma(body)) {
                String segment = rawSegment.trim();
                if (!segment.matches("(?is).*\\bIDENTITY\\s*\\(.*")) {
                    continue;
                }

                String columnName = normalizeIdentifierKey(extractLeadingIdentifier(segment));
                if (!columnName.isBlank()) {
                    identityColumnsByTable
                            .computeIfAbsent(tableName, ignored -> new LinkedHashSet<>())
                            .add(columnName);
                }
            }
        }

        return identityColumnsByTable;
    }

    private List<RoutineForeignKey> parseRoutineForeignKeys(String schemaContext) {
        if (schemaContext == null || schemaContext.isBlank()) {
            return List.of();
        }

        List<RoutineForeignKey> foreignKeys = new ArrayList<>();
        foreignKeys.addAll(parseRoutineForeignKeysFromSchemaJson(schemaContext));

        Map<String, ParsedCreateTable> createTables = parseCreateTableSql(schemaContext);
        for (ParsedCreateTable table : createTables.values()) {
            for (ParsedForeignKey foreignKey : table.foreignKeys()) {
                foreignKeys.add(new RoutineForeignKey(
                        normalizeIdentifierKey(table.tableName()),
                        normalizeIdentifierKeys(foreignKey.columns()),
                        normalizeIdentifierKey(foreignKey.referencesTable()),
                        normalizeIdentifierKeys(foreignKey.referencesColumns())));
            }
        }

        Matcher matcher = ALTER_TABLE_FOREIGN_KEY_PATTERN.matcher(schemaContext);
        while (matcher.find()) {
            foreignKeys.add(new RoutineForeignKey(
                    normalizeIdentifierKey(matcher.group(1)),
                    normalizeIdentifierKeys(splitIdentifiers(matcher.group(2))),
                    normalizeIdentifierKey(matcher.group(3)),
                    normalizeIdentifierKeys(splitIdentifiers(matcher.group(4)))));
        }

        return foreignKeys;
    }

    private List<String> validateTriggerRubricHeuristics(JsonNode rubricNode, String schemaContext) {
        List<String> issues = new ArrayList<>();
        JsonNode testCases = rubricNode.path("test_cases");

        if (!testCases.isArray() || testCases.isEmpty()) {
            issues.add("- test_cases phải là mảng không rỗng.");
            return issues;
        }

        double totalScoreWeight = 0.0d;

        for (int i = 0; i < testCases.size(); i++) {
            JsonNode tc = testCases.get(i);
            String label = "TC" + (i + 1) + " (" + tc.path("case_name").asText("unnamed") + ")";
            String setup = tc.path("setup_script").asText("");
            String invocation = tc.path("invocation_query").asText("");
            String validation = tc.path("validation_query").asText("");
            String combined = setup + "\n" + invocation + "\n" + validation;

            if (tc.has("score_weight") && tc.get("score_weight").isNumber()) {
                totalScoreWeight += Math.abs(tc.get("score_weight").asDouble());
            }

            if (invocation.isBlank()) {
                issues.add("- " + label + ": invocation_query là bắt buộc (DML kích hoạt trigger).");
            }

            if (combined.matches("(?is).*\\{SCHEMA\\}.*") && !combined.matches("(?is).*\\[\\{SCHEMA\\}\\].*")) {
                issues.add("- " + label + ": dùng [{SCHEMA}], không dùng {SCHEMA} làm giá trị thay thế schema.");
            }

            if (combined.matches("(?is).*\\bdbo\\s*\\..*")
                    || combined.matches("(?is).*\\[\\s*dbo\\s*\\]\\s*\\..*")) {
                issues.add("- " + label + ": không dùng dbo.; hãy dùng [{SCHEMA}].ObjectName ở mọi nơi.");
            }

            if (setup.matches("(?is).*\\bINSERT\\s+INTO\\b.*")
                    && !setup.matches("(?is).*\\bDELETE\\s+FROM\\b.*")) {
                issues.add("- " + label + ": setup_script INSERT dữ liệu test nhưng chưa DELETE các khóa đó trước. "
                        + "Hãy làm setup có thể chạy lặp lại để tránh lỗi trùng khóa chính.");
            }

            if (setup.matches("(?is).*\\b(?:CREATE|ALTER|DROP)\\s+TABLE\\b.*")) {
                issues.add("- " + label + ": setup_script không được CREATE/ALTER/DROP bảng nền. "
                        + "DDL của đặc tả đề thi đã được nạp; chỉ seed dữ liệu bằng DELETE/INSERT/UPDATE.");
            }

            // Check if INSERT has correct number of VALUES
            if (combined.matches("(?is).*\\bINSERT\\s+INTO\\b.*")) {
                // This is a heuristic check - we can't fully validate without parsing
                if (schemaContext != null && !schemaContext.isBlank()) {
                    // Extract table names from INSERT statements
                    java.util.regex.Pattern insertPattern = java.util.regex.Pattern.compile(
                            "(?i)\\bINSERT\\s+INTO\\s+(?:\\[?\\{SCHEMA\\}\\]?|\\[[^\\]]+\\])\\.\\[?([A-Za-z0-9_]+)\\]?\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)",
                            java.util.regex.Pattern.DOTALL);
                    java.util.regex.Matcher insertMatcher = insertPattern.matcher(combined);
                    while (insertMatcher.find()) {
                        String tableName = insertMatcher.group(1);
                        String columns = insertMatcher.group(2);
                        String values = insertMatcher.group(3);
                        int columnCount = columns.split(",").length;
                        int valueCount = values.split(",").length;
                        if (columnCount != valueCount) {
                            issues.add("- " + label + ": INSERT INTO " + tableName + " có " + columnCount
                                    + " cột nhưng có " + valueCount + " giá trị. Hai số lượng này phải khớp.");
                        }
                    }
                }
            }
        }

        if (Math.abs(totalScoreWeight - 1.0d) > 0.01d) {
            issues.add("- Tổng score_weight của test_cases phải bằng 1.0. Tổng hiện tại là "
                    + String.format(Locale.ROOT, "%.2f", totalScoreWeight) + ".");
        }

        return issues;
    }

    private List<RoutineForeignKey> parseRoutineForeignKeysFromSchemaJson(String schemaContext) {
        String trimmed = schemaContext == null ? "" : schemaContext.trim();
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(trimmed);
            JsonNode tables = root.isArray() ? root : firstArray(root, "tables", "databaseSpec", "schema", "items");
            if (tables == null || !tables.isArray()) {
                return List.of();
            }

            List<RoutineForeignKey> foreignKeys = new ArrayList<>();
            for (JsonNode table : tables) {
                String tableName = firstText(table, "tableName", "table_name", "name");
                if (tableName.isBlank()) {
                    continue;
                }

                JsonNode columns = firstArray(table, "columns", "columnDefinitions", "fields");
                if (columns == null || !columns.isArray()) {
                    continue;
                }

                for (JsonNode column : columns) {
                    boolean isForeignKey = column.path("foreignKey").asBoolean(false)
                            || column.path("isForeignKey").asBoolean(false)
                            || column.path("foreign_key").asBoolean(false);
                    String referencesTable = firstText(column,
                            "referencesTable", "references_table", "referencedTable", "referenced_table");
                    String referencesColumn = firstText(column,
                            "referencesColumn", "references_column", "referencedColumn", "referenced_column");

                    if (!isForeignKey && referencesTable.isBlank()) {
                        continue;
                    }

                    String columnName = firstText(column, "columnName", "column_name", "name");
                    if (columnName.isBlank() || referencesTable.isBlank()) {
                        continue;
                    }

                    foreignKeys.add(new RoutineForeignKey(
                            normalizeIdentifierKey(tableName),
                            List.of(normalizeIdentifierKey(columnName)),
                            normalizeIdentifierKey(referencesTable),
                            referencesColumn.isBlank()
                                    ? List.of()
                                    : List.of(normalizeIdentifierKey(referencesColumn))));
                }
            }

            return foreignKeys;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private JsonNode firstArray(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isArray()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null) {
            return "";
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("");
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private void addRoutineSetupIdentityIssues(
            List<String> issues,
            String label,
            String setup,
            Map<String, Set<String>> setupInsertColumnsByTable,
            Map<String, Set<String>> identityColumnsByTable) {
        if (setup == null || setup.isBlank()
                || setupInsertColumnsByTable.isEmpty()
                || identityColumnsByTable.isEmpty()) {
            return;
        }

        Set<String> reported = new HashSet<>();
        for (Map.Entry<String, Set<String>> insertEntry : setupInsertColumnsByTable.entrySet()) {
            String tableName = insertEntry.getKey();
            Set<String> identityColumns = identityColumnsByTable.get(tableName);
            if (identityColumns == null || identityColumns.isEmpty()) {
                continue;
            }

            for (String identityColumn : identityColumns) {
                if (!insertEntry.getValue().contains(identityColumn)) {
                    continue;
                }

                String reportKey = tableName + "." + identityColumn;
                if (!reported.add(reportKey)) {
                    continue;
                }

                boolean hasOn = hasIdentityInsertState(setup, tableName, "ON");
                boolean hasOff = hasIdentityInsertState(setup, tableName, "OFF");
                if (!hasOn || !hasOff) {
                    issues.add("- " + label + ": setup_script INSERT giá trị tường minh vào cột IDENTITY "
                            + tableName + "." + identityColumn
                            + " nhưng chưa bọc INSERT của bảng đó bằng cả "
                            + "SET IDENTITY_INSERT [{SCHEMA}]." + tableName + " ON and OFF. "
                            + "Hãy bỏ cột identity và lấy id được sinh, hoặc bật IDENTITY_INSERT "
                            + "ON chỉ cho bảng này, INSERT dòng hợp lệ, rồi tắt OFF trước khi xử lý bảng khác.");
                }
            }
        }
    }

    private void addRoutineSetupForeignKeyIssues(
            List<String> issues,
            String label,
            List<RoutineSetupInsert> setupInserts,
            Map<String, Set<String>> setupInsertColumnsByTable,
            List<RoutineForeignKey> foreignKeys) {
        if (setupInsertColumnsByTable.isEmpty() || foreignKeys.isEmpty()) {
            return;
        }

        Set<String> reported = new HashSet<>();
        for (RoutineForeignKey foreignKey : foreignKeys) {
            if (setupInsertColumnsByTable.containsKey(foreignKey.referencesTable())) {
                continue;
            }

            for (RoutineSetupInsert setupInsert : setupInserts) {
                if (!setupInsert.tableName().equals(foreignKey.tableName())) {
                    continue;
                }

                boolean insertsForeignKeyColumns = foreignKey.columns().isEmpty()
                        || setupInsert.columns().containsAll(foreignKey.columns());
                if (!insertsForeignKeyColumns || !insertHasNonNullValuesForColumns(setupInsert, foreignKey.columns())) {
                    continue;
                }

                String reportKey = foreignKey.tableName() + "->" + foreignKey.referencesTable()
                        + ":" + String.join(",", foreignKey.columns());
                if (!reported.add(reportKey)) {
                    continue;
                }

                issues.add("- " + label + ": setup_script INSERT bảng con " + foreignKey.tableName()
                        + " với cột FK " + formatIdentifierList(foreignKey.columns())
                        + " nhưng chưa INSERT bảng cha tương ứng " + foreignKey.referencesTable()
                        + " trong cùng setup. Hãy INSERT dòng cha trước và không phụ thuộc vào seed data hoặc dòng FK không hợp lệ.");
            }
        }
    }

    private void addRoutineSetupForeignKeyOrderIssues(
            List<String> issues,
            String label,
            List<RoutineSetupInsert> setupInserts,
            List<RoutineForeignKey> foreignKeys) {
        if (setupInserts.isEmpty() || foreignKeys.isEmpty()) {
            return;
        }

        Set<String> tablesInSetup = setupInserts.stream()
                .map(RoutineSetupInsert::tableName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Integer> firstSeenInsertByTable = new LinkedHashMap<>();
        Set<String> reported = new HashSet<>();

        for (RoutineSetupInsert insert : setupInserts) {
            for (RoutineForeignKey foreignKey : foreignKeys) {
                if (!insert.tableName().equals(foreignKey.tableName())) {
                    continue;
                }

                boolean insertsForeignKeyColumns = foreignKey.columns().isEmpty()
                        || insert.columns().containsAll(foreignKey.columns());
                if (!insertsForeignKeyColumns
                        || !insertHasNonNullValuesForColumns(insert, foreignKey.columns())
                        || !tablesInSetup.contains(foreignKey.referencesTable())) {
                    continue;
                }

                if (firstSeenInsertByTable.containsKey(foreignKey.referencesTable())) {
                    continue;
                }

                String reportKey = insert.position() + ":" + foreignKey.tableName() + "->"
                        + foreignKey.referencesTable() + ":" + String.join(",", foreignKey.columns());
                if (!reported.add(reportKey)) {
                    continue;
                }

                issues.add("- " + label + ": setup_script INSERT bảng con " + foreignKey.tableName()
                        + " với cột FK " + formatIdentifierList(foreignKey.columns())
                        + " trước khi INSERT bảng cha " + foreignKey.referencesTable()
                        + ". Hãy INSERT dòng cha trước. Với quan hệ FK vòng, hãy bỏ/null cột FK vòng trước,"
                        + " INSERT cả hai dòng, rồi UPDATE cột FK nếu trạng thái đó là cần thiết.");
            }

            firstSeenInsertByTable.putIfAbsent(insert.tableName(), insert.position());
        }
    }

    private boolean referencesTable(String sql, String tableName) {
        if (sql == null || sql.isBlank() || tableName == null || tableName.isBlank()) {
            return false;
        }

        String identifier = Pattern.quote(tableName);
        Pattern pattern = Pattern.compile(
                "(?is)(?:\\[?\\{SCHEMA\\}\\]?|\\[[^\\]]+\\]|\\b\\w+\\b)\\s*\\.\\s*\\[?"
                        + identifier + "\\]?\\b|\\b" + identifier + "\\b");
        return pattern.matcher(sql).find();
    }

    private Map<String, Set<String>> extractSetupInsertColumnsByTable(List<RoutineSetupInsert> setupInserts) {
        Map<String, Set<String>> insertColumnsByTable = new LinkedHashMap<>();

        for (RoutineSetupInsert setupInsert : setupInserts) {
            if (setupInsert.tableName().isBlank()) {
                continue;
            }

            insertColumnsByTable
                    .computeIfAbsent(setupInsert.tableName(), ignored -> new LinkedHashSet<>())
                    .addAll(setupInsert.columns());
        }

        return insertColumnsByTable;
    }

    private List<RoutineSetupInsert> extractSetupInserts(String setup) {
        List<RoutineSetupInsert> inserts = new ArrayList<>();
        if (setup == null || setup.isBlank()) {
            return inserts;
        }

        Matcher matcher = ROUTINE_SCHEMA_QUALIFIED_INSERT_WITH_COLUMNS_PATTERN.matcher(setup);
        while (matcher.find()) {
            String tableName = normalizeIdentifierKey(matcher.group(1));
            if (tableName.isBlank()) {
                continue;
            }

            List<String> orderedColumns = new ArrayList<>();
            Set<String> columns = new LinkedHashSet<>();
            for (String column : splitIdentifiers(matcher.group(2))) {
                String normalizedColumn = normalizeIdentifierKey(column);
                if (!normalizedColumn.isBlank()) {
                    orderedColumns.add(normalizedColumn);
                    columns.add(normalizedColumn);
                }
            }

            inserts.add(new RoutineSetupInsert(
                    tableName,
                    columns,
                    extractInsertValuesByColumn(setup, matcher.end(), orderedColumns),
                    matcher.start()));
        }

        return inserts;
    }

    private Map<String, String> extractInsertValuesByColumn(String setup, int searchStart, List<String> columns) {
        if (setup == null || columns.isEmpty() || searchStart < 0 || searchStart >= setup.length()) {
            return Map.of();
        }

        Matcher valuesMatcher = Pattern.compile("(?is)\\bVALUES\\s*\\(").matcher(setup);
        if (!valuesMatcher.find(searchStart)) {
            return Map.of();
        }

        int openParenIndex = setup.indexOf('(', valuesMatcher.start());
        int closeParenIndex = findMatchingParen(setup, openParenIndex);
        if (openParenIndex < 0 || closeParenIndex <= openParenIndex) {
            return Map.of();
        }

        List<String> values = splitTopLevelComma(setup.substring(openParenIndex + 1, closeParenIndex));
        Map<String, String> valuesByColumn = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(columns.size(), values.size()); i++) {
            valuesByColumn.put(columns.get(i), values.get(i).trim());
        }
        return valuesByColumn;
    }

    private boolean setupInsertsNonNullForeignKey(
            List<RoutineSetupInsert> setupInserts,
            String tableName,
            List<String> columns) {
        String normalizedTableName = normalizeIdentifierKey(tableName);
        List<String> normalizedColumns = columns.stream()
                .map(this::normalizeIdentifierKey)
                .filter(column -> !column.isBlank())
                .toList();

        for (RoutineSetupInsert setupInsert : setupInserts) {
            if (setupInsert.tableName().equals(normalizedTableName)
                    && setupInsert.columns().containsAll(normalizedColumns)
                    && insertHasNonNullValuesForColumns(setupInsert, normalizedColumns)) {
                return true;
            }
        }
        return false;
    }

    private boolean insertHasNonNullValuesForColumns(RoutineSetupInsert setupInsert, List<String> columns) {
        if (columns.isEmpty()) {
            return true;
        }

        for (String column : columns) {
            String value = setupInsert.valuesByColumn().get(normalizeIdentifierKey(column));
            if (value == null || !isSqlNullLiteral(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSqlNullLiteral(String value) {
        return value != null && value.trim().matches("(?is)^NULL$");
    }

    private boolean hasIdentityInsertState(String setup, String tableName, String state) {
        if (setup == null || tableName == null || state == null) {
            return false;
        }

        Pattern pattern = Pattern.compile(
                "(?is)\\bSET\\s+IDENTITY_INSERT\\s+(?:\\[?\\{SCHEMA\\}\\]?|\\[[^\\]]+\\]|\\{SCHEMA\\})\\s*\\.\\s*\\[?"
                        + Pattern.quote(tableName)
                        + "\\]?\\s+" + Pattern.quote(state) + "\\b");
        return pattern.matcher(setup).find();
    }

    private String normalizeIdentifierKey(String raw) {
        return normalizeIdentifier(raw).toLowerCase(Locale.ROOT);
    }

    private List<String> normalizeIdentifierKeys(List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String key = normalizeIdentifierKey(value);
            if (!key.isBlank()) {
                normalized.add(key);
            }
        }
        return normalized;
    }

    private String formatIdentifierList(List<String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return "(unknown)";
        }
        return String.join(", ", identifiers);
    }

    private void addRoutineSchemaQualificationIssues(List<String> issues, String label, String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }

        Matcher objectMatcher = ROUTINE_SQL_OBJECT_REFERENCE_PATTERN.matcher(sql);
        while (objectMatcher.find()) {
            String objectRef = objectMatcher.group(1);
            if (!isAllowedRoutineSqlObjectReference(objectRef)) {
                issues.add("- " + label + ": object reference '" + objectRef
                        + "' chưa có schema. Hãy dùng [{SCHEMA}].ObjectName trong setup_script, "
                        + "invocation_query và validation_query.");
                return;
            }
        }

        Matcher execMatcher = ROUTINE_SQL_EXEC_REFERENCE_PATTERN.matcher(sql);
        while (execMatcher.find()) {
            String routineRef = execMatcher.group(1);
            if (!isAllowedRoutineSqlObjectReference(routineRef)) {
                issues.add("- " + label + ": EXEC target '" + routineRef
                        + "' chưa có schema. Hãy dùng EXEC ... = [{SCHEMA}].<procedure_name>.");
                return;
            }
        }
    }

    private boolean isAllowedRoutineSqlObjectReference(String objectRef) {
        if (objectRef == null || objectRef.isBlank()) {
            return true;
        }
        String normalized = objectRef.trim().replaceAll("\\s+", "");
        return normalized.startsWith("[{SCHEMA}].")
                || normalized.startsWith("@")
                || normalized.startsWith("#");
    }

    private boolean sideEffectValidationOnlyCountsRows(String validation) {
        if (validation == null || validation.isBlank()
                || !validation.matches("(?is).*\\bCOUNT\\s*\\(.*")) {
            return false;
        }

        Matcher matcher = Pattern.compile("(?is)\\bSELECT\\s+(.*?)\\s+FROM\\b").matcher(validation);
        if (!matcher.find()) {
            return false;
        }

        String selectList = matcher.group(1);
        String[] expressions = selectList.split(",");
        for (String expression : expressions) {
            String normalized = expression
                    .replaceAll("(?is)\\s+AS\\s+\\[[^\\]]+\\]\\s*$", "")
                    .replaceAll("(?is)\\s+AS\\s+[A-Za-z_][A-Za-z0-9_]*\\s*$", "")
                    .trim();
            if (normalized.matches("(?is)@rc\\b.*")
                    || normalized.matches("(?is)return_value\\b.*")
                    || normalized.matches("(?is)COUNT\\s*\\(.*")
                    || normalized.matches("(?is)\\d+")
                    || normalized.matches("(?is)N?'[^']*'")) {
                continue;
            }
            return false;
        }
        return true;
    }

    private boolean looksLikeSuccessScenario(String text) {
        String normalized = normalizeSearchText(text);
        return normalized.contains("success")
                || normalized.contains("thanh cong")
                || normalized.contains("hop le");
    }

    private boolean looksLikeFailureScenario(String text) {
        String normalized = normalizeSearchText(text);
        return normalized.contains("fail")
                || normalized.contains("invalid")
                || normalized.contains("khong")
                || normalized.contains("loi")
                || normalized.contains("that bai")
                || normalized.contains("qua som")
                || normalized.contains("ton tai");
    }

    private boolean looksLikeDeleteScenario(String text) {
        String normalized = normalizeSearchText(text);
        return normalized.contains("delete")
                || normalized.contains("xoa");
    }

    private String normalizeSearchText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String truncateForLog(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
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
            issues.add(
                    "- Không thể phân tích cấu trúc CREATE TABLE từ SQL đáp án mẫu. Hãy trả về đầy đủ mọi bảng và mọi cột trong SQL.");
            return issues;
        }

        if (!rubricTables.isArray()) {
            issues.add("- grading_payload.tables phải là mảng.");
            return issues;
        }

        for (ParsedCreateTable expectedTable : expectedTables.values()) {
            JsonNode rubricTable = findRubricTable(rubricTables, expectedTable.tableName());
            if (rubricTable == null) {
                issues.add("- Rubric thiếu bảng: " + expectedTable.tableName());
                continue;
            }

            JsonNode rubricColumns = rubricTable.path("columns");
            if (!rubricColumns.isArray()) {
                issues.add("- Bảng " + expectedTable.tableName() + " phải có mảng columns.");
                continue;
            }

            if (!expectedTable.columns().isEmpty() && rubricColumns.size() == 0) {
                issues.add("- Bảng " + expectedTable.tableName()
                        + " có cột trong SQL mẫu nhưng columns[] trong rubric đang rỗng.");
            }

            for (String expectedColumn : expectedTable.columns()) {
                if (!rubricHasColumn(rubricColumns, expectedColumn)) {
                    issues.add("- Bảng " + expectedTable.tableName()
                            + " thiếu cột trong rubric: " + expectedColumn);
                }
            }

            JsonNode rubricConstraints = rubricTable.path("constraints");
            for (List<String> primaryKeyColumns : expectedTable.primaryKeys()) {
                if (!rubricHasConstraint(rubricConstraints, "PRIMARY_KEY", primaryKeyColumns, null, null)) {
                    issues.add("- Bảng " + expectedTable.tableName()
                            + " thiếu PRIMARY_KEY trong rubric cho các cột: "
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
                    issues.add("- Bảng " + expectedTable.tableName()
                            + " thiếu FOREIGN_KEY trong rubric cho các cột: "
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
                            || (approximatelyEqual(remainders[i], remainders[bestIndex])
                                    && weights[i] > weights[bestIndex])) {
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
                        && !sameIdentifierList(rubricConstraint.path("references_columns"),
                                expectedReferencesColumns)) {
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

    private record RoutineForeignKey(
            String tableName,
            List<String> columns,
            String referencesTable,
            List<String> referencesColumns) {
    }

    private record RoutineSetupInsert(
            String tableName,
            Set<String> columns,
            Map<String, String> valuesByColumn,
            int position) {
    }

    private record RoutineRubricIssue(
            String caseId,
            String phase,
            String errorCode,
            String message,
            String sql) {
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

    private String buildFunctionRubricPrompt(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String routineType,
            String schemaContext) {
        return String.format(functionRubricPromptTemplate,
                questionContent != null ? questionContent : "Không có nội dung câu hỏi",
                correctQuery,
                routineType,
                totalPoints,
                totalPoints,
                schemaContext != null && !schemaContext.isBlank()
                        ? truncateForLog(schemaContext, 6000)
                        : "No schema context was provided.");
    }

    private String buildStoredProcedureRubricPrompt(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String routineType,
            String schemaContext) {
        return String.format(storedProcedureRubricPromptTemplate,
                questionContent != null ? questionContent : "Không có nội dung câu hỏi",
                correctQuery,
                routineType,
                schemaContext != null && !schemaContext.isBlank()
                        ? truncateForLog(schemaContext, 6000)
                        : "No schema context was provided.",
                totalPoints,
                totalPoints);
    }

    private String buildTriggerRubricPrompt(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String schemaContext) {
        return String.format(triggerRubricPromptTemplate,
                questionContent != null ? questionContent : "Không có nội dung câu hỏi",
                correctQuery,
                totalPoints,
                schemaContext != null && !schemaContext.isBlank() ? schemaContext : "Không có DDL schema context");
    }

    private String wrapTriggerRubric(String rubricJson, double totalPoints) throws Exception {
        JsonNode parsed = objectMapper.readTree(rubricJson);
        if (!(parsed instanceof ObjectNode root)) {
            return rubricJson;
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("question_category", "TRIGGER");
        wrapper.put("total_points", totalPoints);
        wrapper.set("grading_payload", root);

        return objectMapper.writeValueAsString(wrapper);
    }

    @Override
    public JsonNode generateSpecificationSchema(String specificationDescription, JsonNode currentSchemaJson) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Thiếu Gemini API key. Không thể sinh schema.");
            return null;
        }

        HttpClient client = getOrCreateHttpClient();
        if (client == null) {
            return null;
        }

        String currentSchemaText = (currentSchemaJson == null || currentSchemaJson.isNull())
                ? "[]"
                : currentSchemaJson.toString();

        String prompt = String.format(
                specificationSchemaPromptTemplate,
                currentSchemaText,
                specificationDescription == null ? "" : specificationDescription.trim());

        try {
            String requestBody = buildSchemaJsonRequestBody(prompt, 64000);
            log.info("Đang gọi Gemini để sinh schema đặc tả. Độ dài mô tả={}",
                    specificationDescription == null ? 0 : specificationDescription.length());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(geminiEndpoint()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String body = response.body();
                log.error("Gemini API trả lỗi {} khi sinh schema. Đoạn phản hồi: {}",
                        response.statusCode(), safeSnippet(body, 1200));
                throw mapGeminiSchemaError(response.statusCode(), body);
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode candidate = root.path("candidates").get(0);
            if (candidate == null || candidate.isMissingNode()) {
                log.error("Phản hồi sinh schema của Gemini không có candidates. Đoạn phản hồi gốc: {}",
                        safeSnippet(response.body(), 1200));
                return null;
            }

            String text = candidate
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            if (text == null || text.isBlank()) {
                log.error("Phần text trong phản hồi sinh schema của Gemini đang rỗng. Candidate gốc: {}",
                        safeSnippet(candidate.toString(), 1200));
                return null;
            }

            text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            JsonNode parsed = objectMapper.readTree(text);
            if (!parsed.isArray()) {
                log.error("Phản hồi sinh schema của Gemini không phải JSON array. Đoạn đã parse: {}",
                        safeSnippet(parsed.toString(), 1200));
                return null;
            }

            log.info("Gemini sinh schema thành công. Số bảng={}", parsed.size());
            return parsed;
        } catch (Exception e) {
            if (e instanceof BadRequestException badRequestException) {
                throw badRequestException;
            }
            log.error("Không thể sinh schema đặc tả: {}", e.getMessage(), e);
            return null;
        }
    }

    private BadRequestException mapGeminiSchemaError(int statusCode, String responseBody) {
        String providerMessage = extractGeminiProviderMessage(responseBody);
        if (statusCode == 429) {
            String retryDelay = extractRetryDelay(responseBody);
            String retryHint = retryDelay == null ? "" : " Vui lòng thử lại sau " + retryDelay + ".";
            return new BadRequestException(
                    "Hệ thống AI đang vượt quota (Gemini 429)." + retryHint
                            + " Nếu lỗi lặp lại, hãy kiểm tra billing/quota của Gemini.");
        }

        if (providerMessage != null && !providerMessage.isBlank()) {
            return new BadRequestException("Không thể sinh schema từ AI: " + providerMessage);
        }

        return new BadRequestException(
                "Không thể sinh schema từ AI (Gemini HTTP " + statusCode + "). Vui lòng thử lại sau.");
    }

    private String extractGeminiProviderMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode body = objectMapper.readTree(responseBody);
            String message = body.path("error").path("message").asText(null);
            if (message == null || message.isBlank()) {
                return null;
            }
            return safeSnippet(message, 300);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractRetryDelay(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        Matcher matcher = RETRY_DELAY_PATTERN.matcher(responseBody);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private String safeSnippet(String value, int maxLen) {
        if (value == null) {
            return "null";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...(truncated)";
    }

    private String buildSchemaJsonRequestBody(String prompt, int maxOutputTokens) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode contents = root.putArray("contents");
            ObjectNode content = contents.addObject();
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", prompt);

            ObjectNode generationConfig = root.putObject("generationConfig");
            generationConfig.put("temperature", 0.1);
            generationConfig.put("maxOutputTokens", maxOutputTokens);
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.set("responseSchema", buildSpecificationSchemaResponseSchema());

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Không thể tạo request body sinh schema cho Gemini", e);
        }
    }

    private ObjectNode buildSpecificationSchemaResponseSchema() {
        ObjectNode rootArray = objectMapper.createObjectNode();
        rootArray.put("type", "ARRAY");

        ObjectNode tableObject = objectMapper.createObjectNode();
        tableObject.put("type", "OBJECT");
        rootArray.set("items", tableObject);

        ObjectNode tableProps = tableObject.putObject("properties");
        tableProps.putObject("tableName").put("type", "STRING");

        ObjectNode columnsArray = tableProps.putObject("columns");
        columnsArray.put("type", "ARRAY");

        ObjectNode columnObject = objectMapper.createObjectNode();
        columnObject.put("type", "OBJECT");
        columnsArray.set("items", columnObject);

        ObjectNode columnProps = columnObject.putObject("properties");
        columnProps.putObject("columnName").put("type", "STRING");
        columnProps.putObject("dataType").put("type", "STRING");
        columnProps.putObject("primaryKey").put("type", "BOOLEAN");
        columnProps.putObject("foreignKey").put("type", "BOOLEAN");
        columnProps.putObject("referencesTable").put("type", "STRING").put("nullable", true);
        columnProps.putObject("referencesColumn").put("type", "STRING").put("nullable", true);
        columnProps.putObject("nullable").put("type", "BOOLEAN");
        columnProps.putObject("unique").put("type", "BOOLEAN");
        columnProps.putObject("autoIncrement").put("type", "BOOLEAN");

        ArrayNode requiredColumnFields = columnObject.putArray("required");
        requiredColumnFields.add("columnName");
        requiredColumnFields.add("dataType");
        requiredColumnFields.add("primaryKey");
        requiredColumnFields.add("foreignKey");
        requiredColumnFields.add("referencesTable");
        requiredColumnFields.add("referencesColumn");
        requiredColumnFields.add("nullable");
        requiredColumnFields.add("unique");
        requiredColumnFields.add("autoIncrement");

        ArrayNode requiredTableFields = tableObject.putArray("required");
        requiredTableFields.add("tableName");
        requiredTableFields.add("columns");

        return rootArray;
    }

    @Override
    public String generateEntityDescription(String entityName, String displayName,
                                            List<SpecAttribute> attributes,
                                            String schemaContext) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key missing — skipping entity description generation for {}", entityName);
            return null;
        }

        HttpClient client = getOrCreateHttpClient();
        if (client == null) return null;

        String pkList = attributes == null ? "" : attributes.stream()
                .filter(SpecAttribute::isPrimaryKey)
                .map(SpecAttribute::getAttributeName)
                .collect(Collectors.joining(", "));
        String fkHint = attributes == null ? "" : attributes.stream()
                .filter(a -> a.getAttributeName() != null
                        && a.getAttributeName().matches("(?i)^ma[A-Z][A-Za-z0-9]+"))
                .map(SpecAttribute::getAttributeName)
                .collect(Collectors.joining(", "));
        String attrList = attributes == null ? "" : attributes.stream()
                .map(a -> a.getAttributeName() + " (" + a.getDataType() + ")")
                .collect(Collectors.joining(", "));

        String prompt = String.format(entityDescriptionPromptTemplate,
                entityName, displayName != null ? displayName : entityName,
                attrList.isBlank() ? "không có" : attrList,
                pkList.isBlank() ? "không xác định" : pkList,
                fkHint.isBlank() ? "không xác định" : fkHint,
                schemaContext == null || schemaContext.isBlank() ? "không có" : schemaContext);

        String requestBody = buildRequestBody(prompt, 256);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(geminiEndpoint()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Gemini entity description API error {} for entity {}: {}",
                        response.statusCode(), entityName, response.body());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                log.warn("Gemini entity description: empty candidates array for entity {}. Response snippet: {}",
                        entityName, response.body().substring(0, Math.min(200, response.body().length())));
                return null;
            }
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                log.warn("Gemini entity description: empty parts array for entity {}. Response snippet: {}",
                        entityName, response.body().substring(0, Math.min(200, response.body().length())));
                return null;
            }
            String text = parts.get(0).path("text").asText("").trim();

            // Strip control characters; keep only printable Unicode
            text = text.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").trim();
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("Entity description generation failed for {}: {}", entityName, e.getMessage());
            return null;
        }
    }

    private static final AIService.PdfExtractionResult EMPTY_EXTRACTION =
            new AIService.PdfExtractionResult(Collections.emptyList(), "");

    @Override
    public AIService.PdfExtractionResult extractQuestionsFromPdf(byte[] pdfBytes, String schemaContext) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key is missing. Cannot extract questions from PDF.");
            return EMPTY_EXTRACTION;
        }

        HttpClient client = getOrCreateHttpClient();
        if (client == null) {
            return EMPTY_EXTRACTION;
        }

        try {
            String prompt = String.format(extractQuestionsFromPdfPromptTemplate,
                    schemaContext != null && !schemaContext.isBlank() ? schemaContext : "No schema context available");

            String requestBody = buildRequestBodyWithPdf(pdfBytes, prompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(geminiEndpoint()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Gemini PDF extraction error {}: {}", response.statusCode(), response.body());
                return EMPTY_EXTRACTION;
            }

            return parsePdfExtractionResponse(response.body());

        } catch (Exception e) {
            log.error("Failed to extract questions from PDF via Gemini: {}", e.getMessage(), e);
            return EMPTY_EXTRACTION;
        }
    }

    private String buildRequestBodyWithPdf(byte[] pdfBytes, String prompt) throws Exception {
        String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);

        ObjectNode inlineData = objectMapper.createObjectNode();
        inlineData.put("mime_type", "application/pdf");
        inlineData.put("data", base64Pdf);

        ObjectNode pdfPart = objectMapper.createObjectNode();
        pdfPart.set("inline_data", inlineData);

        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("text", prompt);

        ArrayNode parts = objectMapper.createArrayNode();
        parts.add(pdfPart);
        parts.add(textPart);

        ObjectNode content = objectMapper.createObjectNode();
        content.set("parts", parts);

        ArrayNode contents = objectMapper.createArrayNode();
        contents.add(content);

        ObjectNode genConfig = objectMapper.createObjectNode();
        genConfig.put("temperature", 0.1);
        genConfig.put("maxOutputTokens", 8192);

        ObjectNode root = objectMapper.createObjectNode();
        root.set("contents", contents);
        root.set("generationConfig", genConfig);

        return objectMapper.writeValueAsString(root);
    }

    private AIService.PdfExtractionResult parsePdfExtractionResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String text = root
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            JsonNode parsed = objectMapper.readTree(text);
            String schemaScript = parsed.path("schemaScript").asText("").trim();

            JsonNode questionsNode = parsed.path("questions");
            if (!questionsNode.isArray()) {
                log.warn("Gemini PDF response missing 'questions' array");
                return new AIService.PdfExtractionResult(Collections.emptyList(), schemaScript);
            }

            List<AIService.ExtractedQuestion> questions = new ArrayList<>();
            for (JsonNode q : questionsNode) {
                String content = q.path("content").asText("").trim();
                String title = q.path("title").asText("").trim();
                if (title.isBlank()) {
                    title = content.length() > 120 ? content.substring(0, 120).trim() + "..." : content;
                }
                String questionType = q.path("questionType").asText("SELECT_QUERY").trim();
                double points = q.path("points").asDouble(1.0);
                int difficultyLevel = q.path("difficultyLevel").asInt(2);
                int orderIndex = q.path("orderIndex").asInt(questions.size() + 1);

                if (content.isBlank()) continue;

                questions.add(new AIService.ExtractedQuestion(title, content, questionType, points, difficultyLevel, orderIndex));
            }

            return new AIService.PdfExtractionResult(questions, schemaScript);

        } catch (Exception e) {
            log.error("Failed to parse Gemini PDF extraction response: {}", e.getMessage());
            return EMPTY_EXTRACTION;
        }
    }
}
