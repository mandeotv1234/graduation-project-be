package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.SubmitFeedbackResponse;
import java.time.LocalDateTime;

public record SubmitFeedbackResponseDto(
        Long id,
        Long studentId,
        Long examId,
        Integer uiUxRating,
        Integer systemReliabilityRating,
        Integer npsScore,
        String featureRequests,
        String generalFeedback,
        LocalDateTime createdAt
) {
    public static SubmitFeedbackResponseDto fromResponse(SubmitFeedbackResponse response) {
        return new SubmitFeedbackResponseDto(
                response.id(),
                response.studentId(),
                response.examId(),
                response.uiUxRating(),
                response.systemReliabilityRating(),
                response.npsScore(),
                response.featureRequests(),
                response.generalFeedback(),
                response.createdAt()
        );
    }
}
