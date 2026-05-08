package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Feedback;
import java.time.LocalDateTime;

public record SubmitFeedbackResponse(
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
    public static SubmitFeedbackResponse fromModel(Feedback feedback) {
        return new SubmitFeedbackResponse(
                feedback.getId(),
                feedback.getStudentId(),
                feedback.getExamId(),
                feedback.getUiUxRating(),
                feedback.getSystemReliabilityRating(),
                feedback.getNpsScore(),
                feedback.getFeatureRequests(),
                feedback.getGeneralFeedback(),
                feedback.getCreatedAt()
        );
    }
}
