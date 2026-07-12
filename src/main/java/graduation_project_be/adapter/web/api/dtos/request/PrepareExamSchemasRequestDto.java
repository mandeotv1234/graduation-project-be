package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.PrepareExamSchemasRequest;

public record PrepareExamSchemasRequestDto(Boolean forceRebuild) {

    public PrepareExamSchemasRequest toRequest(Long examId) {
        return new PrepareExamSchemasRequest(examId, Boolean.TRUE.equals(forceRebuild));
    }
}
