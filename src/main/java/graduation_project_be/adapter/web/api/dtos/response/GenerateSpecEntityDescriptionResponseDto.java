package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GenerateSpecEntityDescriptionResponse;

public record GenerateSpecEntityDescriptionResponseDto(
        Long entityId,
        String entityName,
        String description
) {
    public static GenerateSpecEntityDescriptionResponseDto fromResponse(GenerateSpecEntityDescriptionResponse r) {
        return new GenerateSpecEntityDescriptionResponseDto(r.entityId(), r.entityName(), r.description());
    }
}
