package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.SubmitExamResponse;
import graduation_project_be.domain.models.enums.GradingStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;
import java.util.stream.Collectors;

public record SubmitExamResponseDto(
        Long resultId,
        Long examId,
        Long studentId,
        LocalDateTime submittedAt,
        GradingStatus status,
        BigDecimal totalScore,
        BigDecimal maxScore,
        Integer correctCount,
        Integer totalQuestions,
        List<SubmissionDetailDto> details,
        List<QuestionResultDto> questionResults) {

    public record SubmissionDetailDto(
            Long questionId,
            String content,
            BigDecimal points,
            String studentQuery
    ) {
        public static SubmissionDetailDto fromResponse(SubmitExamResponse.SubmissionDetail r) {
            return new SubmissionDetailDto(r.questionId(), r.content(), r.points(), r.studentQuery());
        }
    }

    public record QuestionResultDto(
            Long submissionId,
            Long questionId,
            int orderIndex,
            String studentQuery,
            boolean isCorrect,
            BigDecimal scoreEarned,
            BigDecimal maxPoints,
            String errorMessage,
            Integer executionTimeMs
    ) {
        public static QuestionResultDto fromResponse(SubmitExamResponse.QuestionResultItem r) {
            return new QuestionResultDto(
                    r.submissionId(), r.questionId(), r.orderIndex(), r.studentQuery(),
                    r.isCorrect(), r.scoreEarned(), r.maxPoints(), r.errorMessage(), r.executionTimeMs());
        }
    }

    public static SubmitExamResponseDto fromResponse(SubmitExamResponse r) {
        List<SubmissionDetailDto> detailDtos = r.details() != null ? r.details().stream()
                .map(SubmissionDetailDto::fromResponse)
                .collect(Collectors.toList()) : null;

        List<QuestionResultDto> resultDtos = r.questionResults() != null ? r.questionResults().stream()
                .map(QuestionResultDto::fromResponse)
                .collect(Collectors.toList()) : null;

        return new SubmitExamResponseDto(
                r.resultId(), r.examId(), r.studentId(), r.submittedAt(), r.status(),
                r.totalScore(), r.maxScore(), r.correctCount(), r.totalQuestions(),
                detailDtos, resultDtos);
    }
}
