package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.OverrideSubmissionScoreResponse;

import java.math.BigDecimal;

public record OverrideSubmissionResponseDto(
        Long submissionId,
        BigDecimal scoreEarned,
        boolean isCorrect,
        String gradingType,
        String teacherComment,
        UpdatedResultDto updatedResult) {

    public static OverrideSubmissionResponseDto fromResponse(OverrideSubmissionScoreResponse r) {
        return new OverrideSubmissionResponseDto(
                r.submissionId(),
                r.scoreEarned(),
                r.isCorrect(),
                r.gradingType(),
                r.teacherComment(),
                new UpdatedResultDto(
                        r.updatedResult().totalScore(),
                        r.updatedResult().correctCount(),
                        r.updatedResult().gradingType()));
    }

    public record UpdatedResultDto(
            BigDecimal totalScore,
            int correctCount,
            String gradingType) {
    }
}
