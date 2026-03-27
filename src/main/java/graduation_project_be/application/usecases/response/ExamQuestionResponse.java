package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.ExamQuestion;
import java.math.BigDecimal;

public record ExamQuestionResponse(
        Long id,
        Long examId,
        String content,
        String correctQuery,
        Integer difficultyLevel,
        BigDecimal points,
        Integer orderIndex,
        String questionType,
        String verifyScript,
        String gradingRubric) {
    public static ExamQuestionResponse fromModel(ExamQuestion q) {
        return new ExamQuestionResponse(
                q.getId(), q.getExamId(), q.getContent(), q.getCorrectQuery(),
                q.getDifficultyLevel(), q.getPoints(), q.getOrderIndex(),
                q.getQuestionType().name(), q.getVerifyScript(), q.getGradingRubric());
    }
}
