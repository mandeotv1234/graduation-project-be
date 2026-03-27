package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

@Slf4j
@Service
public class GeminiServiceImpl implements GeminiService {

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=";

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

    private String systemPromptTemplate;
    private String createTableRubricPromptTemplate;
    private String insertDataRubricPromptTemplate;
    private String selectQueryRubricPromptTemplate;

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

        String basePrompt = "INSERT_DATA".equalsIgnoreCase(questionType)
            ? buildInsertRubricPrompt(correctQuery, questionContent, totalPoints)
            : "SELECT_QUERY".equalsIgnoreCase(questionType)
                ? buildSelectRubricPrompt(correctQuery, questionContent, totalPoints, priorQuestionContext)
                : buildCreateTableRubricPrompt(correctQuery, questionContent, totalPoints);

        try {
            if (!"SELECT_QUERY".equalsIgnoreCase(questionType)) {
                return callGeminiForJson(client, basePrompt);
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
                    return latestJson;
                }

                if (attempt == 2) {
                    log.warn("SELECT rubric still has heuristic issues after retries: {}", issues);
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

    private String buildCreateTableRubricPrompt(String correctQuery, String questionContent, double totalPoints) {
        return String.format(createTableRubricPromptTemplate,
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
