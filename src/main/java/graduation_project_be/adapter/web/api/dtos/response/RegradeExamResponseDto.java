package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.RegradeExamResponse;

import java.math.BigDecimal;
import java.util.List;

public record RegradeExamResponseDto(
    String message,
    PreviousScoresDto previousScores
) {
    public static RegradeExamResponseDto fromResponse(RegradeExamResponse r) {
        return new RegradeExamResponseDto(
            r.message(),
            PreviousScoresDto.fromResponse(r.previousScores())
        );
    }

    public record PreviousScoresDto(
        BigDecimal totalScore,
        int correctCount,
        List<QuestionSnapshotDto> details
    ) {
        public static PreviousScoresDto fromResponse(RegradeExamResponse.PreviousScores s) {
            return new PreviousScoresDto(
                s.totalScore(),
                s.correctCount(),
                s.details().stream().map(QuestionSnapshotDto::fromResponse).toList()
            );
        }
    }

    public record QuestionSnapshotDto(
        Long questionId,
        BigDecimal scoreEarned,
        boolean isCorrect
    ) {
        public static QuestionSnapshotDto fromResponse(RegradeExamResponse.QuestionSnapshot q) {
            return new QuestionSnapshotDto(q.questionId(), q.scoreEarned(), q.isCorrect());
        }
    }
}
