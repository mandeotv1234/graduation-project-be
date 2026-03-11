package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.TeacherNotification;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table(name = "teacher_notifications")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "student_name", length = 255)
    private String studentName;

    @Column(name = "violation_type", nullable = false, length = 50)
    private String violationType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "violation_count")
    private long violationCount;

    @Column(name = "auto_submitted")
    private boolean autoSubmitted;

    @Column(name = "is_read")
    private boolean isRead;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public TeacherNotification toModel() {
        return TeacherNotification.builder()
                .id(id)
                .teacherId(teacherId)
                .examId(examId)
                .studentId(studentId)
                .studentName(studentName)
                .violationType(violationType)
                .description(description)
                .violationCount(violationCount)
                .autoSubmitted(autoSubmitted)
                .isRead(isRead)
                .createdAt(createdAt)
                .build();
    }

    public static TeacherNotificationEntity fromModel(TeacherNotification model) {
        return TeacherNotificationEntity.builder()
                .id(model.getId())
                .teacherId(model.getTeacherId())
                .examId(model.getExamId())
                .studentId(model.getStudentId())
                .studentName(model.getStudentName())
                .violationType(model.getViolationType())
                .description(model.getDescription())
                .violationCount(model.getViolationCount())
                .autoSubmitted(model.isAutoSubmitted())
                .isRead(model.isRead())
                .build();
    }
}
