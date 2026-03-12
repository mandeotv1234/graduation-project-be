package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamSpecification;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Table(name = "exam_specifications")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamSpecificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false, unique = true)
    private Long examId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "specification", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("orderIndex ASC")
    private List<SpecEntityEntity> entities;

    public ExamSpecification toModel() {
        List<graduation_project_be.domain.models.SpecEntity> entityModels = entities == null ? List.of()
                : entities.stream().map(SpecEntityEntity::toModel).toList();
        return ExamSpecification.builder()
                .id(id)
                .examId(examId)
                .title(title)
                .description(description)
                .entities(entityModels)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    public static ExamSpecificationEntity fromModel(ExamSpecification model) {
        return ExamSpecificationEntity.builder()
                .id(model.getId())
                .examId(model.getExamId())
                .title(model.getTitle())
                .description(model.getDescription())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();
    }
}
