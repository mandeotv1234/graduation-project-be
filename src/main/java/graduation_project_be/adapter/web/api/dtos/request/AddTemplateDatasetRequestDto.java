package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.AddTemplateDatasetRequest;
import jakarta.validation.constraints.NotBlank;

public record AddTemplateDatasetRequestDto(
        @NotBlank(message = "Data script is required") String dataScript) {
    public AddTemplateDatasetRequest toRequest(Long templateId) {
        return new AddTemplateDatasetRequest(templateId, dataScript);
    }
}
