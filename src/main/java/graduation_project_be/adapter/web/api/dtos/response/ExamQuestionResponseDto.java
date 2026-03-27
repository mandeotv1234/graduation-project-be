package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import java.math.BigDecimal;

public record ExamQuestionResponseDto(
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
    public static ExamQuestionResponseDto fromResponse(ExamQuestionResponse r) {
        return new ExamQuestionResponseDto(
                r.id(), r.examId(), r.content(), r.correctQuery(),
                r.difficultyLevel(), r.points(), r.orderIndex(),
                r.questionType(), r.verifyScript(), r.gradingRubric());
    }
}
