package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.ExamSpecification;

import java.time.LocalDateTime;

public record SpecificationResponse(
        Long id,
        String name,
        String ddlScript,
        String description,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static SpecificationResponse fromModel(ExamSpecification spec) {
        return new SpecificationResponse(
                spec.getId(),
                spec.getName(),
                spec.getDdlScript(),
                spec.getDescription(),
                spec.getCreatedBy(),
                spec.getCreatedAt(),
                spec.getUpdatedAt());
    }
}
