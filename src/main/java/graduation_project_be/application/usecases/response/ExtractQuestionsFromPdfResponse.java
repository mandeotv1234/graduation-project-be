package graduation_project_be.application.usecases.response;

import java.util.List;

public record ExtractQuestionsFromPdfResponse(List<QuestionDraft> questions, String schemaScript) {

    public record QuestionDraft(
            String title,
            String content,
            String questionType,
            double points,
            int difficultyLevel,
            int orderIndex) {
    }
}
