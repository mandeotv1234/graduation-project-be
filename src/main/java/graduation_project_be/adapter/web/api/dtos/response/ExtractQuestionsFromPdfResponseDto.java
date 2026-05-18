package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.ExtractQuestionsFromPdfResponse;

import java.util.List;

public record ExtractQuestionsFromPdfResponseDto(List<QuestionDraftDto> questions, String schemaScript) {

    public record QuestionDraftDto(
            String title,
            String content,
            String questionType,
            double points,
            int difficultyLevel,
            int orderIndex) {
    }

    public static ExtractQuestionsFromPdfResponseDto fromResponse(ExtractQuestionsFromPdfResponse response) {
        List<QuestionDraftDto> drafts = response.questions().stream()
                .map(q -> new QuestionDraftDto(q.title(), q.content(), q.questionType(), q.points(), q.difficultyLevel(),
                        q.orderIndex()))
                .toList();
        return new ExtractQuestionsFromPdfResponseDto(drafts, response.schemaScript());
    }
}
