package graduation_project_be.application.usecases.request;

public record ClearExamSchemaRequest(
        Long examId,
        String ipAddress,
        String userAgent
) {
}
