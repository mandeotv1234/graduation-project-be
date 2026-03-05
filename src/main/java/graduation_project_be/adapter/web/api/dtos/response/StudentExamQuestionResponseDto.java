package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import java.math.BigDecimal;

/**
 * Student-facing DTO that omits solution fields (correctQuery, verifyScript).
 */
public record StudentExamQuestionResponseDto(
        Long id,
        Long examId,
        String content,
        BigDecimal points,
        Integer orderIndex,
        String questionType) {
    public static StudentExamQuestionResponseDto fromResponse(ExamQuestionResponse r) {
        return new StudentExamQuestionResponseDto(
                r.id(), r.examId(), r.content(),
                r.points(), r.orderIndex(), r.questionType());
    }
}
