package graduation_project_be.application.usecases.request;

public record GetExamMonitorRequest(
        Long examId,
        int page,
        int size,
        String keyword,
        String riskFilter,
        String examStatusFilter,
        int highRiskThreshold,
        String sortColumn,
        String sortDirection) {
}
