package graduation_project_be.application.usecases.request;

import java.math.BigDecimal;

public record OverrideSubmissionScoreRequest(
        BigDecimal scoreEarned,
        Boolean isCorrect,
        String teacherComment) {
}
