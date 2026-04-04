package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.UpdateExamQuestionRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record UpdateExamQuestionRequestDto(
        @NotBlank(message = "Content is required") String content,
        String correctQuery,
        String verifyScript,
        Integer difficultyLevel,
        @NotNull(message = "Points are required") @Positive BigDecimal points,
        Integer orderIndex,
        @NotBlank(message = "Question type is required") String questionType,
        String gradingRubric) {

    public UpdateExamQuestionRequest toRequest(Long examId, Long questionId) {
        return new UpdateExamQuestionRequest(examId, questionId, content, correctQuery, verifyScript, difficultyLevel, points, orderIndex, questionType, gradingRubric);
    }
}
