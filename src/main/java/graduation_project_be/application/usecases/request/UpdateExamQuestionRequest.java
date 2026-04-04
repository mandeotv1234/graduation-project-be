package graduation_project_be.application.usecases.request;

import java.math.BigDecimal;

public record UpdateExamQuestionRequest(
        Long examId,
        Long questionId,
        String content,
        String correctQuery,
        String verifyScript,
        Integer difficultyLevel,
        BigDecimal points,
        Integer orderIndex,
        String questionType,
        String gradingRubric) {
}
