package graduation_project_be.adapter.web.api.dtos.request;

import jakarta.validation.constraints.NotBlank;

public record GenerateSchemaFromDdlRequestDto(
        @NotBlank(message = "ddlScript is required") String ddlScript) {
}
