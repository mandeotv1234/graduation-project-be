package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.OverrideSubmissionScoreRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record OverrideSubmissionRequestDto(
        @NotNull(message = "scoreEarned is required")
        @DecimalMin(value = "0", message = "scoreEarned must be >= 0")
        BigDecimal scoreEarned,

        @NotNull(message = "isCorrect is required")
        Boolean isCorrect,

        @Size(max = 1000, message = "teacherComment must not exceed 1000 characters")
        String teacherComment) {

    public OverrideSubmissionScoreRequest toRequest() {
        return new OverrideSubmissionScoreRequest(scoreEarned, isCorrect, teacherComment);
    }
}
