package graduation_project_be.application.port.services;

import com.fasterxml.jackson.databind.JsonNode;

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

    record GeneratedQuestion(String correctQuery, String verifyScript) {}
}

