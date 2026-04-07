package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.enums.GradingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record GetExamResultDetailResponse(
    Long submissionId,
    Long studentId,
    String studentName,
    String studentEmail,
    int attemptNumber,
    BigDecimal totalScore,
    BigDecimal maxScore,
    int correctCount,
    int totalQuestions,
    GradingStatus status,
    LocalDateTime submittedAt,
    String gradingType,
    LocalDateTime lastGradedAt,
    List<QuestionResultDetail> questionResults
) {
    public record QuestionResultDetail(
        Long questionId,
        Long submissionId,
        String content,
        String studentQuery,
        String correctQuery,
        boolean isCorrect,
        BigDecimal scoreEarned,
        BigDecimal maxPoints,
        String errorMessage,
        Integer executionTimeMs,
        String questionType,
        String gradingType,
        Long gradedBy,
        String gradedByName,
        LocalDateTime gradedAt,
        String teacherComment
    ) {}
}
