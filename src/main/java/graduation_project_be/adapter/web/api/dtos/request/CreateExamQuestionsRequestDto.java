package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateExamQuestionsRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.constraints.DecimalMax;

public record CreateExamQuestionsRequestDto(
        String schemaContext,
        @NotEmpty(message = "Questions list must not be empty")
        @Valid
        List<QuestionItemDto> questions) {

    public record QuestionItemDto(
            @NotBlank(message = "Content is required") String content,
            String correctQuery,
            String verifyScript,
            @Min(value = 1, message = "Difficulty level must be at least 1")
            @Max(value = 5, message = "Difficulty level must not exceed 5")
            Integer difficultyLevel,
            @NotNull(message = "Points are required")
            @DecimalMin(value = "0.1", message = "Points must be at least 0.1")
            @DecimalMax(value = "10.0", message = "Points must not exceed 10")
            @Digits(integer = 2, fraction = 2, message = "Points must have at most 2 decimal places")
            BigDecimal points,
            @Positive(message = "Order index must be positive") Integer orderIndex,
            @NotBlank(message = "Question type is required")
            @Pattern(
                    regexp = "CREATE_TABLE|INSERT_DATA|SELECT_QUERY|TRIGGER|FUNCTION|STORED_PROCEDURE",
                    message = "Question type is invalid")
            String questionType,
            String gradingRubric) {
    }

    public CreateExamQuestionsRequest toRequest(Long examId) {
        List<CreateExamQuestionsRequest.QuestionItem> items = questions.stream()
                .map(q -> new CreateExamQuestionsRequest.QuestionItem(
                        q.content(), q.correctQuery(), q.verifyScript(),
                        q.difficultyLevel(), q.points(),
                        q.orderIndex(), q.questionType(), q.gradingRubric()))
                .toList();
        return new CreateExamQuestionsRequest(examId, schemaContext, items);
    }
}
