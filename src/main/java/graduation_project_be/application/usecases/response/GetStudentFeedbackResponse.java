package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.enums.GradingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record GetStudentFeedbackResponse(
        Long resultId,
        Long examId,
        String examTitle,
        int attemptNumber,
        BigDecimal totalScore,
        BigDecimal maxScore,
        LocalDateTime submittedAt,
        boolean generatedByAi,
        LocalDateTime generatedAt,
        ProgressSummary progress,
        String overallFeedback,
        String progressFeedback,
        List<String> strengths,
        List<String> weaknesses,
        List<String> studyAdvice,
        List<QuestionFeedback> questionFeedbacks) {

    public record ProgressSummary(
            int attemptCount,
            BigDecimal firstScore,
            BigDecimal currentScore,
            BigDecimal bestScore,
            BigDecimal averageScore,
            double improvementFromFirstPercent,
            Double currentAttemptDeltaPercent,
            List<AttemptPoint> attempts) {}

    public record AttemptPoint(
            Long resultId,
            int attemptNumber,
            BigDecimal totalScore,
            BigDecimal maxScore,
            LocalDateTime submittedAt,
            GradingStatus status) {}

    public record QuestionFeedback(
            Long questionId,
            int orderIndex,
            String questionType,
            BigDecimal scoreEarned,
            BigDecimal maxPoints,
            String diagnosis,
            List<String> mistakes,
            List<String> advice,
            List<TraceEvidence> evidence) {}

    public record TraceEvidence(
            String kind,
            String status,
            String label,
            String message,
            BigDecimal deductedPoints,
            String expected,
            String actual) {}
}
