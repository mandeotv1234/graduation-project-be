package graduation_project_be.application.usecases.request;

public record PrepareExamSchemasRequest(
        Long examId,
        boolean forceRebuild) {
}
