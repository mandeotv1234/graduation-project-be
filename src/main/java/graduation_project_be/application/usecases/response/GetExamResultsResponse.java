package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.enums.GradingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record GetExamResultsResponse(
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
}
