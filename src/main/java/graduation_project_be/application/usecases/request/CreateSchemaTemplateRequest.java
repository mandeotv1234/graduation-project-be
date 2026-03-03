package graduation_project_be.application.usecases.request;

public record CreateSchemaTemplateRequest(
        String name,
        String ddlScript,
        String defaultDataScript) {
}
