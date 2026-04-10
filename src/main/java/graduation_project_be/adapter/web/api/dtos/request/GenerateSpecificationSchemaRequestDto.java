package graduation_project_be.adapter.web.api.dtos.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

public record GenerateSpecificationSchemaRequestDto(
        @NotBlank(message = "Description is required") String description,
        JsonNode currentSchemaJson) {
}
