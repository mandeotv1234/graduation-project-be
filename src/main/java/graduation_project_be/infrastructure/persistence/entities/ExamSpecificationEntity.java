package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamSpecification;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Table(name = "specifications")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamSpecificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "ddl_script", nullable = false, columnDefinition = "TEXT")
    private String ddlScript;

    @Column(name = "schema_json", columnDefinition = "TEXT")
    private String schemaJson;

    @Column(name = "schema_diagram", columnDefinition = "TEXT")
    private String schemaDiagram;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "specification", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("orderIndex ASC")
    private List<SpecEntityEntity> entities;

    @OneToMany(mappedBy = "specification", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("orderIndex ASC")
    private List<SpecDatasetEntity> datasets;

    public ExamSpecification toModel() {
        List<graduation_project_be.domain.models.SpecEntity> entityModels = entities == null ? List.of()
                : entities.stream().map(SpecEntityEntity::toModel).toList();
        return ExamSpecification.builder()
                .id(id)
                .name(name)
                .ddlScript(ddlScript)
                .schemaJson(schemaJson)
                .schemaDiagram(schemaDiagram)
                .description(description)
                .entities(entityModels)
                .datasets(datasets == null ? List.of() : datasets.stream().map(SpecDatasetEntity::toModel).toList())
                .createdBy(createdBy)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    public static ExamSpecificationEntity fromModel(ExamSpecification model) {
        return ExamSpecificationEntity.builder()
                .id(model.getId())
                .name(model.getName())
                .ddlScript(model.getDdlScript())
                .schemaJson(model.getSchemaJson())
                .schemaDiagram(model.getSchemaDiagram())
                .description(model.getDescription())
                .createdBy(model.getCreatedBy())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();
    }
}
