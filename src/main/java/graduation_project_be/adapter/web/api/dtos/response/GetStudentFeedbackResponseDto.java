package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetStudentFeedbackResponse;
import graduation_project_be.domain.models.enums.GradingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record GetStudentFeedbackResponseDto(
        Long resultId,
        Long examId,
        String examTitle,
        int attemptNumber,
        BigDecimal totalScore,
        BigDecimal maxScore,
        LocalDateTime submittedAt,
        boolean generatedByAi,
        LocalDateTime generatedAt,
        ProgressSummaryDto progress,
        String overallFeedback,
        String progressFeedback,
        List<String> strengths,
        List<String> weaknesses,
        List<String> studyAdvice,
        List<QuestionFeedbackDto> questionFeedbacks) {

    public static GetStudentFeedbackResponseDto fromResponse(GetStudentFeedbackResponse response) {
        return new GetStudentFeedbackResponseDto(
                response.resultId(),
                response.examId(),
                response.examTitle(),
                response.attemptNumber(),
                response.totalScore(),
                response.maxScore(),
                response.submittedAt(),
                response.generatedByAi(),
                response.generatedAt(),
                ProgressSummaryDto.fromResponse(response.progress()),
                response.overallFeedback(),
                response.progressFeedback(),
                response.strengths(),
                response.weaknesses(),
                response.studyAdvice(),
                response.questionFeedbacks().stream()
                        .map(QuestionFeedbackDto::fromResponse)
                        .toList());
    }

    public record ProgressSummaryDto(
            int attemptCount,
            BigDecimal firstScore,
            BigDecimal currentScore,
            BigDecimal bestScore,
            BigDecimal averageScore,
            double improvementFromFirstPercent,
            Double currentAttemptDeltaPercent,
            List<AttemptPointDto> attempts) {

        public static ProgressSummaryDto fromResponse(GetStudentFeedbackResponse.ProgressSummary progress) {
            return new ProgressSummaryDto(
                    progress.attemptCount(),
                    progress.firstScore(),
                    progress.currentScore(),
                    progress.bestScore(),
                    progress.averageScore(),
                    progress.improvementFromFirstPercent(),
                    progress.currentAttemptDeltaPercent(),
                    progress.attempts().stream()
                            .map(AttemptPointDto::fromResponse)
                            .toList());
        }
    }

    public record AttemptPointDto(
            Long resultId,
            int attemptNumber,
            BigDecimal totalScore,
            BigDecimal maxScore,
            LocalDateTime submittedAt,
            GradingStatus status) {

        public static AttemptPointDto fromResponse(GetStudentFeedbackResponse.AttemptPoint attempt) {
            return new AttemptPointDto(
                    attempt.resultId(),
                    attempt.attemptNumber(),
                    attempt.totalScore(),
                    attempt.maxScore(),
                    attempt.submittedAt(),
                    attempt.status());
        }
    }

    public record QuestionFeedbackDto(
            Long questionId,
            int orderIndex,
            String questionType,
            BigDecimal scoreEarned,
            BigDecimal maxPoints,
            String diagnosis,
            List<String> mistakes,
            List<String> advice,
            List<TraceEvidenceDto> evidence) {

        public static QuestionFeedbackDto fromResponse(GetStudentFeedbackResponse.QuestionFeedback feedback) {
            return new QuestionFeedbackDto(
                    feedback.questionId(),
                    feedback.orderIndex(),
                    feedback.questionType(),
                    feedback.scoreEarned(),
                    feedback.maxPoints(),
                    feedback.diagnosis(),
                    feedback.mistakes(),
                    feedback.advice(),
                    feedback.evidence().stream()
                            .map(TraceEvidenceDto::fromResponse)
                            .toList());
        }
    }

    public record TraceEvidenceDto(
            String kind,
            String status,
            String label,
            String message,
            BigDecimal deductedPoints,
            String expected,
            String actual) {

        public static TraceEvidenceDto fromResponse(GetStudentFeedbackResponse.TraceEvidence evidence) {
            return new TraceEvidenceDto(
                    evidence.kind(),
                    evidence.status(),
                    evidence.label(),
                    evidence.message(),
                    evidence.deductedPoints(),
                    evidence.expected(),
                    evidence.actual());
        }
    }
}
