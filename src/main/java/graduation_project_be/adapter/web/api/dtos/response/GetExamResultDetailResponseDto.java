package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetExamResultDetailResponse;
import graduation_project_be.domain.models.enums.GradingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record GetExamResultDetailResponseDto(
    Long submissionId,
    Long studentId,
    String studentName,
    String studentEmail,
    int attemptNumber,
    BigDecimal totalScore,
    BigDecimal maxScore,
    int correctCount,
    int totalQuestions,
    GradingStatus status,
    LocalDateTime submittedAt,
    List<QuestionResultDetailDto> questionResults
) {
    public static GetExamResultDetailResponseDto fromResponse(GetExamResultDetailResponse r) {
        return new GetExamResultDetailResponseDto(
            r.submissionId(),
            r.studentId(),
            r.studentName(),
            r.studentEmail(),
            r.attemptNumber(),
            r.totalScore(),
            r.maxScore(),
            r.correctCount(),
            r.totalQuestions(),
            r.status(),
            r.submittedAt(),
            r.questionResults().stream()
                .map(QuestionResultDetailDto::fromResponse)
                .toList()
        );
    }

    public record QuestionResultDetailDto(
        Long questionId,
        String content,
        String studentQuery,
        String correctQuery,
        boolean isCorrect,
        BigDecimal scoreEarned,
        BigDecimal maxPoints,
        String errorMessage,
        Integer executionTimeMs
    ) {
        public static QuestionResultDetailDto fromResponse(GetExamResultDetailResponse.QuestionResultDetail d) {
            return new QuestionResultDetailDto(
                d.questionId(), d.content(), d.studentQuery(), d.correctQuery(),
                d.isCorrect(), d.scoreEarned(), d.maxPoints(),
                d.errorMessage(), d.executionTimeMs()
            );
        }
    }
}
