package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Feedback;
import graduation_project_be.domain.models.User;

import java.time.LocalDateTime;

public record GetFeedbacksResponse(
        Long id,
        Long studentId,
        String studentName,
        String studentEmail,
        Long examId,
        Integer uiUxRating,
        Integer systemReliabilityRating,
        Integer npsScore,
        String featureRequests,
        String generalFeedback,
        LocalDateTime createdAt
) {
    public static GetFeedbacksResponse fromModel(Feedback feedback, User student) {
        String name = student != null ? student.getFullName() : "Unknown";
        String email = student != null ? student.getEmail() : "Unknown";
        return new GetFeedbacksResponse(
                feedback.getId(),
                feedback.getStudentId(),
                name,
                email,
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
