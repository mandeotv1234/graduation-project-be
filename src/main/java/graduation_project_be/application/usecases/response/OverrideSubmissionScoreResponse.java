package graduation_project_be.application.usecases.response;

import java.math.BigDecimal;

public record OverrideSubmissionScoreResponse(
        Long submissionId,
        BigDecimal scoreEarned,
        boolean isCorrect,
        String gradingType,
        String teacherComment,
        UpdatedResult updatedResult) {

    public record UpdatedResult(
            BigDecimal totalScore,
            int correctCount,
            String gradingType) {
    }
}
