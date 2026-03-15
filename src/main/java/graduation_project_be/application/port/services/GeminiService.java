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

    record GeneratedQuestion(String correctQuery, String verifyScript) {}
}
