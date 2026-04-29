package graduation_project_be.application.usecases.request;

public record GetExamResultsRequest(
        Long examId,
        int page,
        int size,
        String keyword,
        String scoreFilter,
        String encounterMode,
        String sortOrder) {
}
