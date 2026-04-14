package graduation_project_be.application.usecases.request;

public record ExecuteSelectQueryRequest(
        Long examId,
        String setupDependencyId,
        String setupCustomScript,
        String correctQuery) {
}
