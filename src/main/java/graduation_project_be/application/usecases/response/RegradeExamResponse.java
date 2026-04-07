package graduation_project_be.application.usecases.response;

import java.math.BigDecimal;
import java.util.List;

public record RegradeExamResponse(
    String message,
    PreviousScores previousScores
) {
    public record PreviousScores(
        BigDecimal totalScore,
        int correctCount,
        List<QuestionSnapshot> details
    ) {}

    public record QuestionSnapshot(
        Long questionId,
        BigDecimal scoreEarned,
        boolean isCorrect
    ) {}
}
