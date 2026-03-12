package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.SpecAttribute;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Table(name = "spec_attributes")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecAttributeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id", nullable = false)
    private SpecEntityEntity entity;

    @Column(name = "attribute_name", nullable = false)
    private String attributeName;

    @Column(name = "data_type", nullable = false)
    private String dataType;

    @Column(name = "description")
    private String description;

    @Column(name = "is_primary_key")
    private boolean isPrimaryKey;

    @Column(name = "is_nullable")
    private boolean isNullable;

    @Column(name = "order_index")
    private int orderIndex;

    public SpecAttribute toModel() {
        return SpecAttribute.builder()
                .id(id)
                .entityId(entity != null ? entity.getId() : null)
                .attributeName(attributeName)
                .dataType(dataType)
                .description(description)
                .isPrimaryKey(isPrimaryKey)
                .isNullable(isNullable)
                .orderIndex(orderIndex)
                .build();
    }

    public static SpecAttributeEntity fromModel(SpecAttribute model, SpecEntityEntity entityEntity) {
        return SpecAttributeEntity.builder()
                .id(model.getId())
                .entity(entityEntity)
                .attributeName(model.getAttributeName())
                .dataType(model.getDataType())
                .description(model.getDescription())
                .isPrimaryKey(model.isPrimaryKey())
                .isNullable(model.isNullable())
                .orderIndex(model.getOrderIndex())
                .build();
    }
}
