package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.Exam;
import jakarta.persistence.*;
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

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "exam_matrix", columnDefinition = "JSON")
    private String examMatrix;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "is_published")
    private Boolean isPublished;

    public Exam toModel() {
        return Exam.builder()
                .id(id)
                .templateId(templateId)
                .classId(classId)
                .creatorId(creatorId)
                .examMatrix(examMatrix)
                .durationMinutes(durationMinutes)
                .startTime(startTime)
                .endTime(endTime)
                .isPublished(isPublished)
                .build();
    }

    public static ExamEntity fromModel(Exam exam) {
        return ExamEntity.builder()
                .id(exam.getId())
                .templateId(exam.getTemplateId())
                .classId(exam.getClassId())
                .creatorId(exam.getCreatorId())
                .examMatrix(exam.getExamMatrix())
                .durationMinutes(exam.getDurationMinutes())
                .startTime(exam.getStartTime())
                .endTime(exam.getEndTime())
                .isPublished(exam.getIsPublished())
                .build();
    }
}
