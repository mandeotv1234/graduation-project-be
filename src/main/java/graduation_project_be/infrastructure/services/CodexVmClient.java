package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
public class CodexVmClient {

    private static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private final String apiUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CodexVmClient(
            @Value("${spring.application.codex.api-url}") String apiUrl,
            @Value("${spring.application.codex.api-key}") String apiKey) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Send a prompt to Codex on VM, return raw text response.
     * Caller is responsible for parsing (JSON, plain text, etc.).
     */
    public String ask(String prompt, int timeoutSeconds) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", prompt);

        String requestBody = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/ask"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Codex VM API error " + response.statusCode() + ": " + safeSnippet(response.body(), 500));
        }

        JsonNode result = objectMapper.readTree(response.body());
        String text = result.path("response").asText();
        log.debug("Codex VM response length={}", text.length());
        return text;
    }

    /**
     * Ask Codex and strip markdown code fences from response.
     * Use when expecting JSON back.
     */
    public String askForJson(String prompt, int timeoutSeconds) throws IOException, InterruptedException {
        String raw = ask(prompt, timeoutSeconds);
        return stripMarkdown(raw);
    }

    public String ask(String prompt) throws IOException, InterruptedException {
        return ask(prompt, DEFAULT_TIMEOUT_SECONDS);
    }

    public String askForJson(String prompt) throws IOException, InterruptedException {
        return askForJson(prompt, DEFAULT_TIMEOUT_SECONDS);
    }

    static String stripMarkdown(String text) {
        if (text == null) return null;
        return text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
    }

    private String safeSnippet(String value, int maxLen) {
        if (value == null) return "null";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...(truncated)";
    }
}
