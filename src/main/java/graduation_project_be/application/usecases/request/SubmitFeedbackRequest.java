package graduation_project_be.application.usecases.request;

public record SubmitFeedbackRequest(
        Long examId,
        Long studentId,
        Integer uiUxRating,
        Integer systemReliabilityRating,
        Integer npsScore,
        String featureRequests,
        String generalFeedback
) {}
