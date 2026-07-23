package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateExamQuestionRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateExamQuestionRequestDto(
        @NotBlank(message = "Content is required") String content,
        String correctQuery,
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
        String verifyScript) {
    public CreateExamQuestionRequest toRequest(Long examId) {
        return new CreateExamQuestionRequest(examId, content, correctQuery, difficultyLevel, points, orderIndex,
                questionType, verifyScript);
    }
}
