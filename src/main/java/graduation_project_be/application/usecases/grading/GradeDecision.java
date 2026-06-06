package graduation_project_be.application.usecases.grading;

import java.math.BigDecimal;

/** Result of grading one question: correctness, message, and earned score. */
public record GradeDecision(boolean isCorrect, String errorMessage, BigDecimal scoreEarned) {
    public static GradeDecision pass(BigDecimal scoreEarned) {
        return new GradeDecision(true, null, scoreEarned == null ? BigDecimal.ZERO : scoreEarned);
    }

    public static GradeDecision fail(String message) {
        return new GradeDecision(false, message, BigDecimal.ZERO);
    }

    public static GradeDecision partial(BigDecimal scoreEarned, String message) {
        return new GradeDecision(false, message, scoreEarned == null ? BigDecimal.ZERO : scoreEarned);
    }
}
