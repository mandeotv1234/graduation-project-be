package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.ExecuteSelectQueryRequest;

public record ExecuteSelectQueryRequestDto(
        String setupDependencyId,
        String setupCustomScript,
        String correctQuery) {

    public ExecuteSelectQueryRequest toRequest(Long examId) {
        return new ExecuteSelectQueryRequest(
                examId,
                setupDependencyId == null ? "" : setupDependencyId,
                setupCustomScript == null ? "" : setupCustomScript,
                correctQuery == null ? "" : correctQuery);
    }
}
