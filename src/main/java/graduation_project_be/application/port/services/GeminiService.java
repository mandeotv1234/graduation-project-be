package graduation_project_be.application.port.services;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.domain.models.SpecAttribute;

import java.util.List;

public interface GeminiService {
    /**
     * Given a question content + questionType, ask Gemini to generate
     * correctQuery and verifyScript for an SQL exam question.
     */
    GeminiService.GeneratedQuestion generateSqlAnswer(
            String questionContent,
            String questionType,
            String schemaContext);

    /**
     * Given a CREATE TABLE SQL statement, ask Gemini to generate
     * a structured grading rubric JSON for partial scoring.
     */
    String generateGradingRubric(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String questionType,
            String priorQuestionContext,
            String schemaContext);

    /**
     * Generate DB schema JSON from natural-language specification description.
     */
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

    record GeneratedQuestion(String correctQuery, String verifyScript) {
    }
}
