package graduation_project_be.application.usecases.response;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.SpecEntity;

public record ExamSpecificationResponse(
        Long id,
        String name,
        String ddlScript,
        JsonNode schemaJson,
        String schemaDiagram,
        String description,
        List<SpecEntityResponse> entities,
        List<SpecDatasetResponse> datasets,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    private static final ObjectMapper mapper = new ObjectMapper();

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

    public record SpecDatasetResponse(
            Long id,
            String name,
            String dataScript,
            String tableData,
            int orderIndex,
            boolean isActive) {
    }

    public static ExamSpecificationResponse fromModel(ExamSpecification model) {
        List<SpecEntityResponse> entityResponses = model.getEntities() == null ? List.of()
                : model.getEntities().stream().map(ExamSpecificationResponse::toEntityResponse).toList();
        List<SpecDatasetResponse> datasetResponses = model.getDatasets() == null ? List.of()
                : model.getDatasets().stream().map(ExamSpecificationResponse::toDatasetResponse).toList();
        
        JsonNode jsonNode = null;
        if (model.getSchemaJson() != null && !model.getSchemaJson().isBlank()) {
            try {
                jsonNode = mapper.readTree(model.getSchemaJson());
            } catch (Exception e) {
                // ignore or fallback
            }
        }

        String schemaDiagram = model.getSchemaDiagram();
        if ((schemaDiagram == null || schemaDiagram.isBlank())
                && model.getSchemaJson() != null
                && !model.getSchemaJson().isBlank()) {
            schemaDiagram = model.getSchemaJson();
        }

        return new ExamSpecificationResponse(
                model.getId(),
                model.getName(),
                model.getDdlScript(),
                jsonNode,
                schemaDiagram,
                model.getDescription(),
                entityResponses,
                datasetResponses,
                model.getCreatedBy(),
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

    private static SpecDatasetResponse toDatasetResponse(SpecDataset dataset) {
        return new SpecDatasetResponse(
                dataset.getId(),
                dataset.getName(),
                dataset.getDataScript(),
                dataset.getTableData(),
                dataset.getOrderIndex(),
                dataset.isActive());
    }
}
