package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.domain.models.SpecAttribute;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpenAiServiceImpl implements AIService {
    private static final int OPENAI_TIMEOUT_SECONDS = 180;
    private static final int PDF_IMAGE_RENDER_DPI = 144;
    private static final int PDF_IMAGE_MAX_PAGES = 8;
    private static final AIService.PdfExtractionResult EMPTY_EXTRACTION =
            new AIService.PdfExtractionResult(Collections.emptyList(), "");

    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final ObjectMapper objectMapper;
    private volatile HttpClient httpClient;

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
    private String triggerRubricPromptTemplate;
    private String specificationSchemaPromptTemplate;
    private String createTableRulesPromptTemplate;
    private String entityDescriptionPromptTemplate;
    private String extractQuestionsFromPdfPromptTemplate;

    public OpenAiServiceImpl(
            @Value("${spring.application.openai.api-key:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${spring.application.openai.api-url:https://api.openai.com/v1}") String apiUrl,
            @Value("${spring.application.openai.model:gpt-5.4}") String model) {
        this.apiKey = apiKey;
        this.apiUrl = trimTrailingSlash(apiUrl);
        this.model = model;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        try {
            this.systemPromptTemplate = load(systemPromptResource);
            this.createTableRubricPromptTemplate = load(createTableRubricPromptResource);
            this.insertDataRubricPromptTemplate = load(insertDataRubricPromptResource);
            this.selectQueryRubricPromptTemplate = load(selectQueryRubricPromptResource);
            this.functionRubricPromptTemplate = load(functionRubricPromptResource);
            this.storedProcedureRubricPromptTemplate = load(storedProcedureRubricPromptResource);
            this.triggerRubricPromptTemplate = load(triggerRubricPromptResource);
            this.specificationSchemaPromptTemplate = load(specificationSchemaPromptResource);
            this.createTableRulesPromptTemplate = load(createTableRulesPromptResource);
            this.entityDescriptionPromptTemplate = load(entityDescriptionPromptResource);
            this.extractQuestionsFromPdfPromptTemplate = load(extractQuestionsFromPdfPromptResource);
        } catch (IOException e) {
            log.error("Failed to load OpenAI prompt templates", e);
            throw new RuntimeException("Failed to load OpenAI prompt templates", e);
        }
    }

    @Override
    public GeneratedQuestion generateSqlAnswer(String questionContent, String questionType, String schemaContext) {
        String prompt = String.format(systemPromptTemplate,
                schemaContext != null && !schemaContext.isBlank() ? schemaContext : "No schema context was provided.",
                questionType,
                questionContent);

        try {
            String text = callOpenAiForJson(prompt, 8000);
            return parseGeneratedQuestion(text);
        } catch (Exception e) {
            log.error("OpenAI SQL answer generation failed: {}", e.getMessage(), e);
            return new GeneratedQuestion("-- AI generation failed: " + e.getMessage(), null);
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
        try {
            String prompt = buildRubricPrompt(
                    correctQuery,
                    questionContent,
                    totalPoints,
                    questionType,
                    priorQuestionContext,
                    schemaContext);
            String rubricJson = callOpenAiForJson(prompt, 12000);
            if ("TRIGGER".equalsIgnoreCase(questionType)) {
                return wrapTriggerRubric(rubricJson, totalPoints);
            }
            return rubricJson;
        } catch (Exception e) {
            log.error("OpenAI rubric generation failed for questionType={}: {}", questionType, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public JsonNode generateSpecificationSchema(String specificationDescription, JsonNode currentSchemaJson) {
        String currentSchemaText = currentSchemaJson == null || currentSchemaJson.isNull()
                ? "[]"
                : currentSchemaJson.toString();
        String prompt = String.format(specificationSchemaPromptTemplate,
                currentSchemaText,
                specificationDescription == null ? "" : specificationDescription.trim());

        try {
            String text = callOpenAi(prompt, false, 12000);
            JsonNode parsed = objectMapper.readTree(text);
            if (!parsed.isArray()) {
                log.error("OpenAI returned a non-array specification schema: {}", safeSnippet(parsed.toString(), 1200));
                return null;
            }
            return parsed;
        } catch (Exception e) {
            log.error("OpenAI specification schema generation failed: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String generateEntityDescription(
            String entityName,
            String displayName,
            List<SpecAttribute> attributes,
            String schemaContext) {
        String pkList = attributes == null ? "" : attributes.stream()
                .filter(SpecAttribute::isPrimaryKey)
                .map(SpecAttribute::getAttributeName)
                .collect(Collectors.joining(", "));
        String fkHint = attributes == null ? "" : attributes.stream()
                .filter(attribute -> attribute.getAttributeName() != null
                        && attribute.getAttributeName().matches("(?i)^ma[A-Z][A-Za-z0-9]+"))
                .map(SpecAttribute::getAttributeName)
                .collect(Collectors.joining(", "));
        String attrList = attributes == null ? "" : attributes.stream()
                .map(attribute -> attribute.getAttributeName() + " (" + attribute.getDataType() + ")")
                .collect(Collectors.joining(", "));

        String prompt = String.format(entityDescriptionPromptTemplate,
                entityName,
                displayName != null ? displayName : entityName,
                attrList.isBlank() ? "khong co" : attrList,
                pkList.isBlank() ? "khong xac dinh" : pkList,
                fkHint.isBlank() ? "khong xac dinh" : fkHint,
                schemaContext == null || schemaContext.isBlank() ? "khong co" : schemaContext);

        try {
            String text = callOpenAi(prompt, false, 1000);
            return text == null || text.isBlank() ? null : text.trim();
        } catch (Exception e) {
            log.warn("OpenAI entity description generation failed for {}: {}", entityName, e.getMessage());
            return null;
        }
    }

    @Override
    public StudentFeedbackDraft generateStudentFeedback(StudentFeedbackContext context) {
        try {
            String prompt = StudentFeedbackAiSupport.buildPrompt(context, objectMapper);
            String text = callOpenAiForJson(prompt, 8000);
            return StudentFeedbackAiSupport.parseDraft(text, objectMapper);
        } catch (Exception e) {
            log.warn("OpenAI student feedback generation failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public PdfExtractionResult extractQuestionsFromPdf(byte[] pdfBytes, String schemaContext) {
        try {
            String basePrompt = String.format(extractQuestionsFromPdfPromptTemplate,
                    schemaContext != null && !schemaContext.isBlank() ? schemaContext : "No schema context available");
            String pdfText = extractTextFromPdf(pdfBytes);
            String text;

            if (pdfText != null && !pdfText.isBlank()) {
                String prompt = basePrompt + "\n\n=== PDF TEXT ===\n" + pdfText;
                text = callOpenAiForJson(prompt, 12000);
            } else {
                log.warn("PDF text extraction returned empty result. Rendering PDF pages for OpenAI vision extraction.");
                List<String> pageImages = renderPdfPagesAsImageDataUrls(pdfBytes);
                if (pageImages.isEmpty()) {
                    log.warn("PDF image rendering returned empty result.");
                    return EMPTY_EXTRACTION;
                }

                String prompt = basePrompt
                        + "\n\n=== PDF RENDERED PAGE IMAGES ===\n"
                        + "PDFBox text extraction returned empty text, so read the attached rendered page images. "
                        + "Extract schemaScript and questions only from visible text in these images.";
                text = callOpenAiForJsonWithImages(prompt, pageImages, 12000);
            }

            return parsePdfExtractionResponse(text);
        } catch (Exception e) {
            log.error("OpenAI PDF question extraction failed: {}", e.getMessage(), e);
            return EMPTY_EXTRACTION;
        }
    }

    private String buildRubricPrompt(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String questionType,
            String priorQuestionContext,
            String schemaContext) {
        if ("CREATE_TABLE_RULES".equalsIgnoreCase(questionType)) {
            return String.format(Locale.ROOT, createTableRulesPromptTemplate,
                    safeQuestionContent(questionContent),
                    correctQuery,
                    totalPoints,
                    totalPoints);
        }
        if ("INSERT_DATA".equalsIgnoreCase(questionType)) {
            return String.format(Locale.ROOT, insertDataRubricPromptTemplate,
                    safeQuestionContent(questionContent),
                    correctQuery,
                    totalPoints,
                    totalPoints,
                    totalPoints);
        }
        if ("SELECT_QUERY".equalsIgnoreCase(questionType)) {
            return String.format(Locale.ROOT, selectQueryRubricPromptTemplate,
                    safeQuestionContent(questionContent),
                    correctQuery,
                    priorQuestionContext != null && !priorQuestionContext.isBlank()
                            ? priorQuestionContext
                            : "No prior question context was provided.",
                    totalPoints,
                    totalPoints);
        }
        if ("FUNCTION".equalsIgnoreCase(questionType)) {
            return String.format(Locale.ROOT, functionRubricPromptTemplate,
                    safeQuestionContent(questionContent),
                    correctQuery,
                    questionType,
                    totalPoints,
                    totalPoints,
                    schemaContext != null && !schemaContext.isBlank()
                            ? safeSnippet(schemaContext, 6000)
                            : "No schema context was provided.");
        }
        if ("STORED_PROCEDURE".equalsIgnoreCase(questionType)) {
            return String.format(Locale.ROOT, storedProcedureRubricPromptTemplate,
                    safeQuestionContent(questionContent),
                    correctQuery,
                    questionType,
                    schemaContext != null && !schemaContext.isBlank()
                            ? safeSnippet(schemaContext, 6000)
                            : "No schema context was provided.",
                    totalPoints,
                    totalPoints);
        }
        if ("TRIGGER".equalsIgnoreCase(questionType)) {
            return String.format(Locale.ROOT, triggerRubricPromptTemplate,
                    safeQuestionContent(questionContent),
                    correctQuery,
                    totalPoints,
                    schemaContext != null && !schemaContext.isBlank() ? schemaContext : "No schema context was provided.");
        }

        return String.format(Locale.ROOT, createTableRubricPromptTemplate,
                safeQuestionContent(questionContent),
                correctQuery,
                totalPoints,
                totalPoints);
    }

    private GeneratedQuestion parseGeneratedQuestion(String text) {
        try {
            JsonNode result = objectMapper.readTree(stripMarkdown(text));
            String correctQuery = result.path("correctQuery").asText("-- AI did not generate a query");
            String verifyScript = result.path("verifyScript").asText(null);
            return new GeneratedQuestion(correctQuery, verifyScript == null || verifyScript.isBlank() ? null : verifyScript);
        } catch (Exception e) {
            log.error("OpenAI response parsing failed: {}", e.getMessage());
            return new GeneratedQuestion("-- Could not parse AI response", null);
        }
    }

    private String wrapTriggerRubric(String rubricJson, double totalPoints) throws Exception {
        JsonNode parsed = objectMapper.readTree(stripMarkdown(rubricJson));
        if (!(parsed instanceof ObjectNode root)) {
            return rubricJson;
        }
        if (root.has("grading_payload")) {
            return objectMapper.writeValueAsString(root);
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("question_category", "TRIGGER");
        wrapper.put("total_points", totalPoints);
        wrapper.set("grading_payload", root);
        return objectMapper.writeValueAsString(wrapper);
    }

    private PdfExtractionResult parsePdfExtractionResponse(String text) {
        try {
            JsonNode parsed = objectMapper.readTree(stripMarkdown(text));
            String schemaScript = parsed.path("schemaScript").asText("").trim();
            JsonNode questionsNode = parsed.path("questions");
            if (!questionsNode.isArray()) {
                log.warn("OpenAI PDF response missing questions array");
                return new PdfExtractionResult(Collections.emptyList(), schemaScript);
            }

            List<ExtractedQuestion> questions = new ArrayList<>();
            for (JsonNode questionNode : questionsNode) {
                String content = questionNode.path("content").asText("").trim();
                if (content.isBlank()) {
                    continue;
                }

                String title = questionNode.path("title").asText("").trim();
                if (title.isBlank()) {
                    title = content.length() > 120 ? content.substring(0, 120).trim() + "..." : content;
                }
                String questionType = questionNode.path("questionType").asText("SELECT_QUERY").trim();
                double points = questionNode.path("points").asDouble(1.0);
                int difficultyLevel = questionNode.path("difficultyLevel").asInt(2);
                int orderIndex = questionNode.path("orderIndex").asInt(questions.size() + 1);
                questions.add(new ExtractedQuestion(title, content, questionType, points, difficultyLevel, orderIndex));
            }

            return new PdfExtractionResult(questions, schemaScript);
        } catch (Exception e) {
            log.error("OpenAI PDF extraction response parsing failed: {}", e.getMessage());
            return EMPTY_EXTRACTION;
        }
    }

    private String extractTextFromPdf(byte[] pdfBytes) {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception e) {
            log.error("PDF text extraction failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private List<String> renderPdfPagesAsImageDataUrls(byte[] pdfBytes) {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            int pageCount = Math.min(document.getNumberOfPages(), PDF_IMAGE_MAX_PAGES);
            if (document.getNumberOfPages() > PDF_IMAGE_MAX_PAGES) {
                log.warn("PDF has {} pages; rendering first {} pages for OpenAI extraction.",
                        document.getNumberOfPages(), PDF_IMAGE_MAX_PAGES);
            }

            PDFRenderer renderer = new PDFRenderer(document);
            List<String> images = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                BufferedImage image = renderer.renderImageWithDPI(
                        pageIndex,
                        PDF_IMAGE_RENDER_DPI,
                        ImageType.RGB);
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (ImageIO.write(image, "jpg", output) && output.size() > 0) {
                        images.add("data:image/jpeg;base64,"
                                + Base64.getEncoder().encodeToString(output.toByteArray()));
                    }
                }
            }
            return images;
        } catch (Exception e) {
            log.error("PDF image rendering failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String callOpenAiForJson(String prompt, int maxTokens) throws Exception {
        String text = callOpenAi(prompt, true, maxTokens);
        objectMapper.readTree(stripMarkdown(text));
        return stripMarkdown(text);
    }

    private String callOpenAiForJsonWithImages(String prompt, List<String> imageDataUrls, int maxTokens)
            throws Exception {
        String text = callOpenAiWithImages(prompt, imageDataUrls, true, maxTokens);
        objectMapper.readTree(stripMarkdown(text));
        return stripMarkdown(text);
    }

    private String callOpenAi(String prompt, boolean jsonObjectResponse, int maxTokens) throws Exception {
        validateApiKey();

        ObjectNode payload = buildChatCompletionPayload(prompt, jsonObjectResponse, maxTokens, "max_tokens");
        HttpResponse<String> response = sendChatCompletion(payload);

        if (!isSuccess(response) && shouldRetryWithCompletionTokens(response.body())) {
            ObjectNode retryPayload = buildChatCompletionPayload(
                    prompt,
                    jsonObjectResponse,
                    maxTokens,
                    "max_completion_tokens");
            response = sendChatCompletion(retryPayload);
        }

        if (!isSuccess(response) && jsonObjectResponse && mentionsResponseFormat(response.body())) {
            ObjectNode retryPayload = buildChatCompletionPayload(prompt, false, maxTokens, "max_tokens");
            response = sendChatCompletion(retryPayload);
        }

        if (!isSuccess(response)) {
            throw new IllegalStateException("OpenAI API returned HTTP " + response.statusCode() + ": "
                    + safeSnippet(response.body(), 1200));
        }

        return readOpenAiResponseContent(response.body());
    }

    private String callOpenAiWithImages(
            String prompt,
            List<String> imageDataUrls,
            boolean jsonObjectResponse,
            int maxTokens) throws Exception {
        validateApiKey();

        ObjectNode payload = buildChatCompletionPayloadWithImages(
                prompt,
                imageDataUrls,
                jsonObjectResponse,
                maxTokens,
                "max_tokens");
        HttpResponse<String> response = sendChatCompletion(payload);

        if (!isSuccess(response) && shouldRetryWithCompletionTokens(response.body())) {
            ObjectNode retryPayload = buildChatCompletionPayloadWithImages(
                    prompt,
                    imageDataUrls,
                    jsonObjectResponse,
                    maxTokens,
                    "max_completion_tokens");
            response = sendChatCompletion(retryPayload);
        }

        if (!isSuccess(response) && jsonObjectResponse && mentionsResponseFormat(response.body())) {
            ObjectNode retryPayload = buildChatCompletionPayloadWithImages(
                    prompt,
                    imageDataUrls,
                    false,
                    maxTokens,
                    "max_tokens");
            response = sendChatCompletion(retryPayload);
        }

        if (!isSuccess(response)) {
            throw new IllegalStateException("OpenAI API returned HTTP " + response.statusCode() + ": "
                    + safeSnippet(response.body(), 1200));
        }

        return readOpenAiResponseContent(response.body());
    }

    private ObjectNode buildChatCompletionPayload(
            String prompt,
            boolean jsonObjectResponse,
            int maxTokens,
            String tokenFieldName) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put(tokenFieldName, maxTokens);

        if (jsonObjectResponse) {
            payload.putObject("response_format").put("type", "json_object");
        }

        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", jsonObjectResponse
                        ? "Return only valid JSON. Do not wrap the response in markdown."
                        : "Return a direct answer. Do not wrap the response in markdown unless requested.");
        messages.addObject()
                .put("role", "user")
                .put("content", prompt);
        return payload;
    }

    private ObjectNode buildChatCompletionPayloadWithImages(
            String prompt,
            List<String> imageDataUrls,
            boolean jsonObjectResponse,
            int maxTokens,
            String tokenFieldName) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put(tokenFieldName, maxTokens);

        if (jsonObjectResponse) {
            payload.putObject("response_format").put("type", "json_object");
        }

        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", jsonObjectResponse
                        ? "Return only valid JSON. Do not wrap the response in markdown."
                        : "Return a direct answer. Do not wrap the response in markdown unless requested.");

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode content = userMessage.putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", prompt);
        for (String imageDataUrl : imageDataUrls) {
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            ObjectNode imageUrl = imagePart.putObject("image_url");
            imageUrl.put("url", imageDataUrl);
            imageUrl.put("detail", "high");
        }
        return payload;
    }

    private String readOpenAiResponseContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode messageContent = root.path("choices").path(0).path("message").path("content");
        String content = readMessageContent(messageContent);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("OpenAI API returned empty message content");
        }
        return stripMarkdown(content);
    }

    private HttpResponse<String> sendChatCompletion(ObjectNode payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(OPENAI_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        return client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpClient client() {
        HttpClient current = httpClient;
        if (current == null) {
            synchronized (this) {
                current = httpClient;
                if (current == null) {
                    current = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(30))
                            .build();
                    httpClient = current;
                }
            }
        }
        return current;
    }

    private String readMessageContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : contentNode) {
                String text = part.path("text").asText(part.path("content").asText(""));
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
            return String.join("", parts);
        }
        return contentNode.toString();
    }

    private boolean isSuccess(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private boolean shouldRetryWithCompletionTokens(String body) {
        String normalized = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return normalized.contains("max_tokens") && normalized.contains("max_completion_tokens");
    }

    private boolean mentionsResponseFormat(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("response_format");
    }

    private void validateApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured");
        }
    }

    private String load(Resource resource) throws IOException {
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    private String safeQuestionContent(String questionContent) {
        return questionContent != null ? questionContent : "No question content was provided.";
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

    private String stripMarkdown(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.openai.com/v1";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
