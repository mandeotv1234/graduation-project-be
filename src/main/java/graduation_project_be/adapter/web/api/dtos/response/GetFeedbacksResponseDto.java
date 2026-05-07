package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetFeedbacksResponse;

import java.time.LocalDateTime;

public record GetFeedbacksResponseDto(
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
    public static GetFeedbacksResponseDto fromResponse(GetFeedbacksResponse response) {
        return new GetFeedbacksResponseDto(
                response.id(),
                response.studentId(),
                response.studentName(),
                response.studentEmail(),
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
