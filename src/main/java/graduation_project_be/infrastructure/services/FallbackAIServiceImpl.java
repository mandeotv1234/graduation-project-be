package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.domain.models.SpecAttribute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

@Slf4j
@Primary
@Service
public class FallbackAIServiceImpl implements AIService {
    private static final PdfExtractionResult EMPTY_EXTRACTION =
            new PdfExtractionResult(Collections.emptyList(), "");

    private final List<Provider> providers;
    private final ObjectMapper objectMapper;

    public FallbackAIServiceImpl(
            CodexServiceImpl codexService,
            ClaudeServiceImpl claudeService,
            OpenAiServiceImpl openAiService) {
        this.providers = List.of(
                new Provider("Codex", codexService),
                new Provider("Claude", claudeService),
                new Provider("OpenAI", openAiService));
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public GeneratedQuestion generateSqlAnswer(String questionContent, String questionType, String schemaContext) {
        return callWithFallback(
                "generateSqlAnswer",
                provider -> provider.generateSqlAnswer(questionContent, questionType, schemaContext),
                this::isValidGeneratedQuestion,
                new GeneratedQuestion(null, null));
    }

    @Override
    public String generateGradingRubric(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String questionType,
            String priorQuestionContext,
            String schemaContext) {
        return callWithFallback(
                "generateGradingRubric",
                provider -> provider.generateGradingRubric(
                        correctQuery,
                        questionContent,
                        totalPoints,
                        questionType,
                        priorQuestionContext,
                        schemaContext),
                this::isValidJson,
                null);
    }

    @Override
    public String refineGradingRubricTestCases(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String questionType,
            String priorQuestionContext,
            String schemaContext,
            String currentRubricJson,
            String teacherInstruction,
            String targetMode,
            String targetTestCaseId) {
        return callWithFallback(
                "refineGradingRubricTestCases",
                provider -> provider.refineGradingRubricTestCases(
                        correctQuery,
                        questionContent,
                        totalPoints,
                        questionType,
                        priorQuestionContext,
                        schemaContext,
                        currentRubricJson,
                        teacherInstruction,
                        targetMode,
                        targetTestCaseId),
                this::isValidJson,
                null);
    }

    @Override
    public JsonNode generateSpecificationSchema(String specificationDescription, JsonNode currentSchemaJson) {
        return callWithFallback(
                "generateSpecificationSchema",
                provider -> provider.generateSpecificationSchema(specificationDescription, currentSchemaJson),
                this::isValidSpecificationSchema,
                null);
    }

    @Override
    public String generateEntityDescription(
            String entityName,
            String displayName,
            List<SpecAttribute> attributes,
            String schemaContext) {
        return callWithFallback(
                "generateEntityDescription",
                provider -> provider.generateEntityDescription(entityName, displayName, attributes, schemaContext),
                this::isNonBlank,
                null);
    }

    @Override
    public PdfExtractionResult extractQuestionsFromPdf(byte[] pdfBytes, String schemaContext) {
        return callWithFallback(
                "extractQuestionsFromPdf",
                provider -> provider.extractQuestionsFromPdf(pdfBytes, schemaContext),
                this::isValidPdfExtraction,
                EMPTY_EXTRACTION);
    }

    @Override
    public StudentFeedbackDraft generateStudentFeedback(StudentFeedbackContext context) {
        return callWithFallback(
                "generateStudentFeedback",
                provider -> provider.generateStudentFeedback(context),
                this::isValidStudentFeedback,
                null);
    }

    private <T> T callWithFallback(
            String operation,
            Function<AIService, T> call,
            Predicate<T> validResult,
            T fallbackValue) {
        for (Provider provider : providers) {
            try {
                T result = call.apply(provider.service());
                if (validResult.test(result)) {
                    log.info("AI provider {} handled {}", provider.name(), operation);
                    return result;
                }
                log.warn("AI provider {} returned empty or invalid result for {}. Trying next provider.",
                        provider.name(), operation);
            } catch (Exception e) {
                log.warn("AI provider {} failed {}: {}. Trying next provider.",
                        provider.name(), operation, e.getMessage());
            }
        }

        log.error("All AI providers failed {}", operation);
        return fallbackValue;
    }

    private boolean isValidGeneratedQuestion(GeneratedQuestion result) {
        if (result == null || result.correctQuery() == null || result.correctQuery().isBlank()) {
            return false;
        }

        String normalized = result.correctQuery().trim().toLowerCase(Locale.ROOT);
        return !(normalized.startsWith("--")
                && (normalized.contains("failed")
                || normalized.contains("thất bại")
                || normalized.contains("không thể")
                || normalized.contains("khong the")
                || normalized.contains("did not generate")
                || normalized.contains("không sinh")
                || normalized.contains("khong sinh")));
    }

    private boolean isValidJson(String value) {
        if (!isNonBlank(value)) {
            return false;
        }
        try {
            objectMapper.readTree(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isValidSpecificationSchema(JsonNode value) {
        return value != null && value.isArray() && !value.isEmpty();
    }

    private boolean isValidPdfExtraction(PdfExtractionResult result) {
        return result != null
                && ((result.questions() != null && !result.questions().isEmpty())
                || isNonBlank(result.schemaScript()));
    }

    private boolean isValidStudentFeedback(StudentFeedbackDraft result) {
        return result != null
                && (isNonBlank(result.overallFeedback())
                || isNonBlank(result.progressFeedback())
                || (result.strengths() != null && !result.strengths().isEmpty())
                || (result.weaknesses() != null && !result.weaknesses().isEmpty())
                || (result.studyAdvice() != null && !result.studyAdvice().isEmpty())
                || (result.questionFeedbacks() != null && !result.questionFeedbacks().isEmpty()));
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record Provider(String name, AIService service) {
    }
}
