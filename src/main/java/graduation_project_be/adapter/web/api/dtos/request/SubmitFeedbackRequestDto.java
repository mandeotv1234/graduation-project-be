package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.SubmitFeedbackRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SubmitFeedbackRequestDto(
        @NotNull(message = "examId is required")
        @Positive(message = "examId must be positive")
        Long examId,

        @NotNull(message = "uiUxRating is required")
        @Min(1) @Max(5)
        Integer uiUxRating,

        @NotNull(message = "systemReliabilityRating is required")
        @Min(1) @Max(5)
        Integer systemReliabilityRating,

        @NotNull(message = "npsScore is required")
        @Min(1) @Max(10)
        Integer npsScore,

        String featureRequests,
        String generalFeedback
) {
    public SubmitFeedbackRequest toRequest(Long studentId) {
        return new SubmitFeedbackRequest(
                examId,
                studentId,
                uiUxRating,
                systemReliabilityRating,
                npsScore,
                featureRequests,
                generalFeedback
        );
    }
}
