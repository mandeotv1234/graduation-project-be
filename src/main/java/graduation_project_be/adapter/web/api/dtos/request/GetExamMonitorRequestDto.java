package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetExamMonitorRequest;

public record GetExamMonitorRequestDto(Long examId) {
    public GetExamMonitorRequest toRequest() {
        return new GetExamMonitorRequest(examId);
    }
}
