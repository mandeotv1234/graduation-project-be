package graduation_project_be.application.usecases.request;

import java.math.BigDecimal;
import java.util.List;

public record CreateExamQuestionsRequest(
        Long examId,
        List<QuestionItem> questions) {

    public record QuestionItem(
            String content,
            String correctQuery,
            String verifyScript,
            Integer difficultyLevel,
            BigDecimal points,
            Integer orderIndex,
            String questionType,
            String gradingRubric) {
    }
}
