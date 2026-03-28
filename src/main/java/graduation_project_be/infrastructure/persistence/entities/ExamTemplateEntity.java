package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamTemplate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "exam_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_exam_id", nullable = false)
    private Long sourceExamId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "shared_by")
    private Long sharedBy;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specification_snapshot", columnDefinition = "jsonb", nullable = false)
    private ExamTemplateSpecificationSnapshotJson specificationSnapshot;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "is_visible", nullable = false)
    private Boolean isVisible;

    public ExamTemplate toModel() {
        return ExamTemplate.builder()
                .id(id)
                .sourceExamId(sourceExamId)
                .version(version)
                .sharedBy(sharedBy)
                .title(title)
                .description(description)
                .specificationSnapshot(specificationSnapshot != null ? specificationSnapshot.toModel() : null)
                .createdAt(createdAt)
                .isVisible(isVisible)
                .build();
    }

    public static ExamTemplateEntity fromModel(ExamTemplate model) {
        return ExamTemplateEntity.builder()
                .id(model.getId())
                .sourceExamId(model.getSourceExamId())
                .version(model.getVersion())
                .sharedBy(model.getSharedBy())
                .title(model.getTitle())
                .description(model.getDescription())
                .specificationSnapshot(ExamTemplateSpecificationSnapshotJson.fromModel(model.getSpecificationSnapshot()))
                .createdAt(model.getCreatedAt())
                .isVisible(model.getIsVisible() != null ? model.getIsVisible() : Boolean.TRUE)
                .build();
    }
}
