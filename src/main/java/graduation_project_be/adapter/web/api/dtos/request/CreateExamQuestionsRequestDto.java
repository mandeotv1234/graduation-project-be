package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateExamQuestionsRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public record CreateExamQuestionsRequestDto(
        @NotEmpty(message = "Questions list must not be empty")
        @Valid
        List<QuestionItemDto> questions) {

    public record QuestionItemDto(
            @NotBlank(message = "Content is required") String content,
            Integer difficultyLevel,
            @NotNull(message = "Points are required") @Positive BigDecimal points,
            Integer orderIndex,
            @NotBlank(message = "Question type is required") String questionType) {
    }

    public CreateExamQuestionsRequest toRequest(Long examId) {
        List<CreateExamQuestionsRequest.QuestionItem> items = questions.stream()
                .map(q -> new CreateExamQuestionsRequest.QuestionItem(
                        q.content(), q.difficultyLevel(), q.points(),
                        q.orderIndex(), q.questionType()))
                .toList();
        return new CreateExamQuestionsRequest(examId, items);
    }
}
