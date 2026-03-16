package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.SpecificationResponse;

import java.time.LocalDateTime;

public record SpecificationResponseDto(
        Long id,
        String name,
        String ddlScript,
        String description,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static SpecificationResponseDto fromResponse(SpecificationResponse r) {
        return new SpecificationResponseDto(
                r.id(), r.name(), r.ddlScript(), r.description(), r.createdBy(), r.createdAt(), r.updatedAt());
    }
}
