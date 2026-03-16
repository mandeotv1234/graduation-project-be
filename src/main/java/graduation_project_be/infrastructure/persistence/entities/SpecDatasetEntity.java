package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.SpecDataset;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table(name = "spec_datasets")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecDatasetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "specification_id", nullable = false)
    private ExamSpecificationEntity specification;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "data_script", nullable = false, columnDefinition = "TEXT")
    private String dataScript;

    @Column(name = "order_index")
    private int orderIndex;

    @Column(name = "is_active")
    private boolean isActive;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public SpecDataset toModel() {
        return SpecDataset.builder()
                .id(id)
                .specificationId(specification != null ? specification.getId() : null)
                .name(name)
                .dataScript(dataScript)
                .orderIndex(orderIndex)
                .isActive(isActive)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    public static SpecDatasetEntity fromModel(SpecDataset model, ExamSpecificationEntity specificationEntity) {
        return SpecDatasetEntity.builder()
                .id(model.getId())
                .specification(specificationEntity)
                .name(model.getName())
                .dataScript(model.getDataScript())
                .orderIndex(model.getOrderIndex())
                .isActive(model.isActive())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();
    }
}
