package graduation_project_be.application.usecases.request;

import java.math.BigDecimal;

public record CreateExamQuestionRequest(
        Long examId,
        String content,
        String correctQuery,
        Integer difficultyLevel,
        BigDecimal points,
        Integer orderIndex,
        String questionType,
        String verifyScript) {
}
