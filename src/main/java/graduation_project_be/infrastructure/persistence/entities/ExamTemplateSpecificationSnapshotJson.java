package graduation_project_be.infrastructure.persistence.entities;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import graduation_project_be.domain.models.ExamTemplateSpecificationSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ExamTemplateSpecificationSnapshotJson {
    private String name;
    private String ddlScript;
    private String description;
    private List<EntitySnapshotJson> entities;
    private List<DatasetSnapshotJson> datasets;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class EntitySnapshotJson {
        private String entityName;
        private String displayName;
        private String description;
        private int orderIndex;
        private List<AttributeSnapshotJson> attributes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class AttributeSnapshotJson {
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
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class DatasetSnapshotJson {
        private String name;
        private String dataScript;
        private int orderIndex;
        private boolean isActive;
    }

    public ExamTemplateSpecificationSnapshot toModel() {
        return ExamTemplateSpecificationSnapshot.builder()
                .name(name)
                .ddlScript(ddlScript)
                .description(description)
                .entities(entities == null
                        ? List.of()
                        : entities.stream()
                        .map(entity -> ExamTemplateSpecificationSnapshot.EntitySnapshot.builder()
                                .entityName(entity.getEntityName())
                                .displayName(entity.getDisplayName())
                                .description(entity.getDescription())
                                .orderIndex(entity.getOrderIndex())
                                .attributes(entity.getAttributes() == null
                                        ? List.of()
                                        : entity.getAttributes().stream()
                                        .map(attribute -> ExamTemplateSpecificationSnapshot.AttributeSnapshot.builder()
                                                .attributeName(attribute.getAttributeName())
                                                .dataType(attribute.getDataType())
                                                .description(attribute.getDescription())
                                                .isPrimaryKey(attribute.isPrimaryKey())
                                                .isNullable(attribute.isNullable())
                                                .orderIndex(attribute.getOrderIndex())
                                                .build())
                                        .toList())
                                .build())
                        .toList())
                .datasets(datasets == null
                        ? List.of()
                        : datasets.stream()
                        .map(dataset -> ExamTemplateSpecificationSnapshot.DatasetSnapshot.builder()
                                .name(dataset.getName())
                                .dataScript(dataset.getDataScript())
                                .orderIndex(dataset.getOrderIndex())
                                .isActive(dataset.isActive())
                                .build())
                        .toList())
                .build();
    }

    public static ExamTemplateSpecificationSnapshotJson fromModel(ExamTemplateSpecificationSnapshot model) {
        if (model == null) {
            return null;
        }

        return ExamTemplateSpecificationSnapshotJson.builder()
                .name(model.getName())
                .ddlScript(model.getDdlScript())
                .description(model.getDescription())
                .entities(model.getEntities() == null
                        ? List.of()
                        : model.getEntities().stream()
                        .map(entity -> EntitySnapshotJson.builder()
                                .entityName(entity.getEntityName())
                                .displayName(entity.getDisplayName())
                                .description(entity.getDescription())
                                .orderIndex(entity.getOrderIndex())
                                .attributes(entity.getAttributes() == null
                                        ? List.of()
                                        : entity.getAttributes().stream()
                                        .map(attribute -> AttributeSnapshotJson.builder()
                                                .attributeName(attribute.getAttributeName())
                                                .dataType(attribute.getDataType())
                                                .description(attribute.getDescription())
                                                .isPrimaryKey(attribute.isPrimaryKey())
                                                .isNullable(attribute.isNullable())
                                                .orderIndex(attribute.getOrderIndex())
                                                .build())
                                        .toList())
                                .build())
                        .toList())
                .datasets(model.getDatasets() == null
                        ? List.of()
                        : model.getDatasets().stream()
                        .map(dataset -> DatasetSnapshotJson.builder()
                                .name(dataset.getName())
                                .dataScript(dataset.getDataScript())
                                .orderIndex(dataset.getOrderIndex())
                                .isActive(dataset.isActive())
                                .build())
                        .toList())
                .build();
    }
}
