package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.Exam;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table(name = "exams")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "specification_id")
    private Long specificationId;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "is_published")
    private Boolean isPublished;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "max_attempts")
    private Integer maxAttempts;

    @Column(name = "late_threshold")
    private Integer lateThreshold;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "json")
    private ExamSettingsJson settings;

    public Exam toModel() {
        return Exam.builder()
                .id(id)
                .specificationId(specificationId)
                .classId(classId)
                .creatorId(creatorId)
                .title(title)
                .durationMinutes(durationMinutes)
                .startTime(startTime)
                .endTime(endTime)
                .isPublished(isPublished)
                .createdAt(createdAt)
                .description(description)
                .maxAttempts(maxAttempts)
                .lateThreshold(lateThreshold)
                .settings(settings != null ? settings.toModel() : null)
                .build();
    }

    public static ExamEntity fromModel(Exam exam) {
        return ExamEntity.builder()
                .id(exam.getId())
                .specificationId(exam.getSpecificationId())
                .classId(exam.getClassId())
                .creatorId(exam.getCreatorId())
                .title(exam.getTitle())
                .durationMinutes(exam.getDurationMinutes())
                .startTime(exam.getStartTime())
                .endTime(exam.getEndTime())
                .isPublished(exam.getIsPublished())
                .createdAt(exam.getCreatedAt())
                .description(exam.getDescription())
                .maxAttempts(exam.getMaxAttempts())
                .lateThreshold(exam.getLateThreshold())
                .settings(ExamSettingsJson.fromModel(exam.getSettings()))
                .build();
    }
}
