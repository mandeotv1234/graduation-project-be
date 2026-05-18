package graduation_project_be.application.port.services;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.domain.models.SpecAttribute;

import java.util.List;

public interface GeminiService {
    GeminiService.GeneratedQuestion generateSqlAnswer(
            String questionContent,
            String questionType,
            String schemaContext);

    String generateGradingRubric(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String questionType,
            String priorQuestionContext,
            String schemaContext);

    JsonNode generateSpecificationSchema(String specificationDescription, JsonNode currentSchemaJson);

    /**
     * Generate a short Vietnamese "tân từ" description (≤2 sentences) for a DB entity.
     * Returns null on failure — callers must handle gracefully.
     */
    String generateEntityDescription(
            String entityName,
            String displayName,
            List<SpecAttribute> attributes,
            String schemaContext);

    GeminiService.PdfExtractionResult extractQuestionsFromPdf(byte[] pdfBytes, String schemaContext);

    record GeneratedQuestion(String correctQuery, String verifyScript) {}

    record ExtractedQuestion(String title, String content, String questionType, double points, int difficultyLevel, int orderIndex) {}

    record PdfExtractionResult(List<ExtractedQuestion> questions, String schemaScript) {}
}
