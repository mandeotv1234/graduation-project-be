package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.SubmitExamResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record SubmitExamResponseDto(
        Long examId,
        Long studentId,
        BigDecimal totalScore,
        BigDecimal maxScore,
        int totalQuestions,
        int correctCount,
        int lateDurationSeconds,
        LocalDateTime submittedAt,
        List<QuestionResultDto> questionResults) {

    public record QuestionResultDto(
            Long submissionId,
            Long questionId,
            Integer orderIndex,
            String studentQuery,
            Boolean isCorrect,
            BigDecimal scoreEarned,
            BigDecimal maxPoints,
            String errorMessage,
            Integer executionTimeMs) {
    }

    public static SubmitExamResponseDto fromResponse(SubmitExamResponse r) {
        List<QuestionResultDto> results = r.questionResults().stream()
                .map(q -> new QuestionResultDto(
                        q.submissionId(), q.questionId(), q.orderIndex(),
                        q.studentQuery(), q.isCorrect(), q.scoreEarned(),
                        q.maxPoints(), q.errorMessage(), q.executionTimeMs()))
                .toList();

        return new SubmitExamResponseDto(
                r.examId(), r.studentId(), r.totalScore(), r.maxScore(),
                r.totalQuestions(), r.correctCount(), r.lateDurationSeconds(), r.submittedAt(), results);
    }
}
