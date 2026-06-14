package graduation_project_be.application.port.services;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.domain.models.SpecAttribute;

import java.util.List;

public interface AIService {
    AIService.GeneratedQuestion generateSqlAnswer(
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

    AIService.PdfExtractionResult extractQuestionsFromPdf(byte[] pdfBytes, String schemaContext);

    default StudentFeedbackDraft generateStudentFeedback(StudentFeedbackContext context) {
        return null;
    }

    record GeneratedQuestion(String correctQuery, String verifyScript) {}

    record ExtractedQuestion(String title, String content, String questionType, double points, int difficultyLevel, int orderIndex) {}

    record PdfExtractionResult(List<ExtractedQuestion> questions, String schemaScript) {}

    record StudentFeedbackContext(
            String examTitle,
            int attemptNumber,
            double totalScore,
            double maxScore,
            double improvementFromFirstPercent,
            Double currentAttemptDeltaPercent,
            List<StudentFeedbackAttempt> attempts,
            List<StudentFeedbackQuestion> questions) {}

    record StudentFeedbackAttempt(
            int attemptNumber,
            double totalScore,
            double maxScore,
            String submittedAt,
            String status) {}

    record StudentFeedbackQuestion(
            Long questionId,
            int orderIndex,
            String questionType,
            String content,
            String studentQuery,
            String correctQuery,
            double scoreEarned,
            double maxPoints,
            String errorMessage,
            List<String> traceMessages) {}

    record StudentFeedbackDraft(
            String overallFeedback,
            String progressFeedback,
            List<String> strengths,
            List<String> weaknesses,
            List<String> studyAdvice,
            List<StudentQuestionFeedbackDraft> questionFeedbacks) {}

    record StudentQuestionFeedbackDraft(
            Long questionId,
            String diagnosis,
            List<String> mistakes,
            List<String> advice) {}
}
