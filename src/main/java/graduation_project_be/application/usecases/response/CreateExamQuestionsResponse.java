package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.ExamQuestion;

import java.math.BigDecimal;
import java.util.List;

public record CreateExamQuestionsResponse(
        int totalCreated,
        List<ExamQuestionResponse> questions) {

    public static CreateExamQuestionsResponse fromModels(List<ExamQuestion> models) {
        List<ExamQuestionResponse> responses = models.stream()
                .map(ExamQuestionResponse::fromModel)
                .toList();
        return new CreateExamQuestionsResponse(responses.size(), responses);
    }
}
