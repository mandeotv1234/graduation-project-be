package graduation_project_be.application.usecases.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record SubmitExamResponse(
        Long examId,
        Long studentId,
        BigDecimal totalScore,
        BigDecimal maxScore,
        int totalQuestions,
        int correctCount,
        LocalDateTime submittedAt,
        List<QuestionResult> questionResults) {

    public record QuestionResult(
            Long submissionId,
            Long questionId,
            Integer orderIndex,
            String studentQuery,
            Boolean isCorrect,
            BigDecimal scoreEarned,
            BigDecimal maxPoints,
            String errorMessage,
            Integer executionTimeMs) {
    }
}
