package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetExamMonitorRequest;

public record GetExamMonitorRequestDto(
        Long examId,
        int page,
        int size,
        String keyword,
        String riskFilter,
        String examStatusFilter,
        int highRiskThreshold,
        String sortColumn,
        String sortDirection) {
    public GetExamMonitorRequest toRequest() {
        return new GetExamMonitorRequest(
                examId,
                page,
                size,
                keyword,
                riskFilter,
                examStatusFilter,
                highRiskThreshold,
                sortColumn,
                sortDirection);
    }
}
