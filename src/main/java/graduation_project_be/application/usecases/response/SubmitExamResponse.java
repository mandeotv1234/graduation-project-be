package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.enums.GradingStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;

public record SubmitExamResponse(
        Long resultId,
        Long examId,
        Long studentId,
        LocalDateTime submittedAt,
        GradingStatus status,
        BigDecimal totalScore,
        BigDecimal maxScore,
        int correctCount,
        int totalQuestions,
        List<SubmissionDetail> details,
        List<QuestionResultItem> questionResults) {

    public record SubmissionDetail(
            Long questionId,
            String content,
            BigDecimal points,
            String studentQuery
    ) {}

    public record QuestionResultItem(
            Long submissionId,
            Long questionId,
            int orderIndex,
            String studentQuery,
            boolean isCorrect,
            BigDecimal scoreEarned,
            BigDecimal maxPoints,
            String errorMessage,
            Integer executionTimeMs
    ) {}
}
