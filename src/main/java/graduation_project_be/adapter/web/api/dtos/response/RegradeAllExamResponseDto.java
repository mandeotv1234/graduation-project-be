package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.RegradeAllExamResponse;

public record RegradeAllExamResponseDto(
    int queuedCount,
    int skippedCount,
    String message
) {
    public static RegradeAllExamResponseDto fromResponse(RegradeAllExamResponse r) {
        return new RegradeAllExamResponseDto(r.queuedCount(), r.skippedCount(), r.message());
    }
}
