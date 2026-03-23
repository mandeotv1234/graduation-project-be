package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateExamQuestionRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateExamQuestionRequestDto(
        @NotBlank(message = "Content is required") String content,
        String correctQuery,
        Integer difficultyLevel,
        @NotNull(message = "Points are required") @Positive BigDecimal points,
        Integer orderIndex,
        @NotBlank(message = "Question type is required") String questionType,
        String verifyScript) {
    public CreateExamQuestionRequest toRequest(Long examId) {
        return new CreateExamQuestionRequest(examId, content, correctQuery, difficultyLevel, points, orderIndex,
                questionType, verifyScript);
    }
}
