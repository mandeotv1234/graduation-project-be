package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.SpecEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Table(name = "spec_entities")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecEntityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "specification_id", nullable = false)
    private ExamSpecificationEntity specification;

    @Column(name = "entity_name", nullable = false)
    private String entityName;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "order_index")
    private int orderIndex;

    @OneToMany(mappedBy = "entity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("orderIndex ASC")
    private List<SpecAttributeEntity> attributes;

    public SpecEntity toModel() {
        List<graduation_project_be.domain.models.SpecAttribute> attrModels = attributes == null ? List.of()
                : attributes.stream().map(SpecAttributeEntity::toModel).toList();
        return SpecEntity.builder()
                .id(id)
                .specificationId(specification != null ? specification.getId() : null)
                .entityName(entityName)
                .displayName(displayName)
                .description(description)
                .orderIndex(orderIndex)
                .attributes(attrModels)
                .build();
    }

    public static SpecEntityEntity fromModel(SpecEntity model, ExamSpecificationEntity specEntity) {
        return SpecEntityEntity.builder()
                .id(model.getId())
                .specification(specEntity)
                .entityName(model.getEntityName())
                .displayName(model.getDisplayName())
                .description(model.getDescription())
                .orderIndex(model.getOrderIndex())
                .build();
    }
}
