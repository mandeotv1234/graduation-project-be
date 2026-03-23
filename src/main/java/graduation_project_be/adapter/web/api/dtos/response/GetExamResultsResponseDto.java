package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetExamResultsResponse;
import graduation_project_be.domain.models.enums.GradingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record GetExamResultsResponseDto(
        Long studentId,
        String studentName,
        String studentEmail,
        int attemptNumber,
        BigDecimal totalScore,
        BigDecimal maxScore,
        int correctCount,
        int totalQuestions,
        GradingStatus status,
        LocalDateTime submittedAt
) {
    public static GetExamResultsResponseDto fromResponse(GetExamResultsResponse r) {
        return new GetExamResultsResponseDto(
                r.studentId(), r.studentName(), r.studentEmail(),
                r.attemptNumber(), r.totalScore(), r.maxScore(),
                r.correctCount(), r.totalQuestions(),
                r.status(), r.submittedAt()
        );
    }
}
