package graduation_project_be.application.port.services;

import graduation_project_be.application.usecases.request.CreateExamQuestionsRequest;

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
            String priorQuestionContext);

    record GeneratedQuestion(String correctQuery, String verifyScript) {}
}
