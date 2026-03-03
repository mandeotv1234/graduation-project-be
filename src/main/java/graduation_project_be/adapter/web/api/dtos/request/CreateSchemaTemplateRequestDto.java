package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateSchemaTemplateRequest;
import jakarta.validation.constraints.NotBlank;

public record CreateSchemaTemplateRequestDto(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "DDL script is required") String ddlScript,
        String defaultDataScript) {
    public CreateSchemaTemplateRequest toRequest() {
        return new CreateSchemaTemplateRequest(name, ddlScript, defaultDataScript);
    }
}
