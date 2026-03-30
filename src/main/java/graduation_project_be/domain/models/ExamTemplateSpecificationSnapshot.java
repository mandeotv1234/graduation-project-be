package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamTemplateSpecificationSnapshot {
    private String name;
    private String ddlScript;
    private String description;
    private List<EntitySnapshot> entities;
    private List<DatasetSnapshot> datasets;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EntitySnapshot {
        private String entityName;
        private String displayName;
        private String description;
        private int orderIndex;
        private List<AttributeSnapshot> attributes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AttributeSnapshot {
        private String attributeName;
        private String dataType;
        private String description;
        private boolean isPrimaryKey;
        private boolean isNullable;
        private int orderIndex;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DatasetSnapshot {
        private String name;
        private String dataScript;
        private int orderIndex;
        private boolean isActive;
    }

    public static ExamTemplateSpecificationSnapshot fromSpecification(ExamSpecification specification) {
        List<EntitySnapshot> entitySnapshots = specification.getEntities() == null
                ? List.of()
                : specification.getEntities().stream()
                .map(entity -> EntitySnapshot.builder()
                        .entityName(entity.getEntityName())
                        .displayName(entity.getDisplayName())
                        .description(entity.getDescription())
                        .orderIndex(entity.getOrderIndex())
                        .attributes(entity.getAttributes() == null
                                ? List.of()
                                : entity.getAttributes().stream()
                                .map(attribute -> AttributeSnapshot.builder()
                                        .attributeName(attribute.getAttributeName())
                                        .dataType(attribute.getDataType())
                                        .description(attribute.getDescription())
                                        .isPrimaryKey(attribute.isPrimaryKey())
                                        .isNullable(attribute.isNullable())
                                        .orderIndex(attribute.getOrderIndex())
                                        .build())
                                .toList())
                        .build())
                .toList();

        List<DatasetSnapshot> datasetSnapshots = specification.getDatasets() == null
                ? List.of()
                : specification.getDatasets().stream()
                .map(dataset -> DatasetSnapshot.builder()
                        .name(dataset.getName())
                        .dataScript(dataset.getDataScript())
                        .orderIndex(dataset.getOrderIndex())
                        .isActive(dataset.isActive())
                        .build())
                .toList();

        return ExamTemplateSpecificationSnapshot.builder()
                .name(specification.getName())
                .ddlScript(specification.getDdlScript())
                .description(specification.getDescription())
                .entities(entitySnapshots)
                .datasets(datasetSnapshots)
                .build();
    }

    public ExamSpecification toSpecification(Long createdBy, LocalDateTime now) {
        List<SpecEntity> specEntities = entities == null
                ? List.of()
                : entities.stream()
                .map(entity -> SpecEntity.builder()
                        .entityName(entity.getEntityName())
                        .displayName(entity.getDisplayName())
                        .description(entity.getDescription())
                        .orderIndex(entity.getOrderIndex())
                        .attributes(entity.getAttributes() == null
                                ? List.of()
                                : entity.getAttributes().stream()
                                .map(attribute -> SpecAttribute.builder()
                                        .attributeName(attribute.getAttributeName())
                                        .dataType(attribute.getDataType())
                                        .description(attribute.getDescription())
                                        .isPrimaryKey(attribute.isPrimaryKey())
                                        .isNullable(attribute.isNullable())
                                        .orderIndex(attribute.getOrderIndex())
                                        .build())
                                .toList())
                        .build())
                .toList();

        List<SpecDataset> specDatasets = datasets == null
                ? List.of()
                : datasets.stream()
                .map(dataset -> SpecDataset.builder()
                        .name(dataset.getName())
                        .dataScript(dataset.getDataScript())
                        .orderIndex(dataset.getOrderIndex())
                        .isActive(dataset.isActive())
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList();

        return ExamSpecification.builder()
                .name(name)
                .ddlScript(ddlScript)
                .description(description)
                .entities(specEntities)
                .datasets(specDatasets)
                .createdBy(createdBy)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
