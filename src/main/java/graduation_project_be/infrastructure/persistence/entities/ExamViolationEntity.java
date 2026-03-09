package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamViolation;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table(name = "exam_violations")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamViolationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "violation_type", nullable = false, length = 50)
    private String violationType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public ExamViolation toModel() {
        return ExamViolation.builder()
                .id(id)
                .examId(examId)
                .studentId(studentId)
                .violationType(violationType)
                .description(description)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .createdAt(createdAt)
                .build();
    }

    public static ExamViolationEntity fromModel(ExamViolation model) {
        return ExamViolationEntity.builder()
                .id(model.getId())
                .examId(model.getExamId())
                .studentId(model.getStudentId())
                .violationType(model.getViolationType())
                .description(model.getDescription())
                .ipAddress(model.getIpAddress())
                .userAgent(model.getUserAgent())
                .build();
    }
}
