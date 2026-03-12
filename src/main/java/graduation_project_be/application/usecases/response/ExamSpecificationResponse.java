package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecEntity;

import java.time.LocalDateTime;
import java.util.List;

public record ExamSpecificationResponse(
        Long id,
        Long examId,
        String title,
        String description,
        List<SpecEntityResponse> entities,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public record SpecEntityResponse(
            Long id,
            String entityName,
            String displayName,
            String description,
            int orderIndex,
            List<SpecAttributeResponse> attributes) {
    }

    public record SpecAttributeResponse(
            Long id,
            String attributeName,
            String dataType,
            String description,
            boolean isPrimaryKey,
            boolean isNullable,
            int orderIndex) {
    }

    public static ExamSpecificationResponse fromModel(ExamSpecification model) {
        List<SpecEntityResponse> entityResponses = model.getEntities() == null ? List.of()
                : model.getEntities().stream().map(ExamSpecificationResponse::toEntityResponse).toList();
        return new ExamSpecificationResponse(
                model.getId(),
                model.getExamId(),
                model.getTitle(),
                model.getDescription(),
                entityResponses,
                model.getCreatedAt(),
                model.getUpdatedAt());
    }

    private static SpecEntityResponse toEntityResponse(SpecEntity entity) {
        List<SpecAttributeResponse> attrResponses = entity.getAttributes() == null ? List.of()
                : entity.getAttributes().stream().map(ExamSpecificationResponse::toAttributeResponse).toList();
        return new SpecEntityResponse(
                entity.getId(),
                entity.getEntityName(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getOrderIndex(),
                attrResponses);
    }

    private static SpecAttributeResponse toAttributeResponse(SpecAttribute attr) {
        return new SpecAttributeResponse(
                attr.getId(),
                attr.getAttributeName(),
                attr.getDataType(),
                attr.getDescription(),
                attr.isPrimaryKey(),
                attr.isNullable(),
                attr.getOrderIndex());
    }
}
