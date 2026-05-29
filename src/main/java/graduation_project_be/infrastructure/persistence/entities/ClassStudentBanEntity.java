package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ClassStudentBan;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table(name = "class_student_bans")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassStudentBanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "banned_by", nullable = false)
    private Long bannedBy;

    @Column(name = "banned_at", insertable = false, updatable = false)
    private LocalDateTime bannedAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    public ClassStudentBan toModel() {
        return ClassStudentBan.builder()
                .id(id)
                .classId(classId)
                .studentId(studentId)
                .reason(reason)
                .bannedBy(bannedBy)
                .bannedAt(bannedAt)
                .active(active)
                .build();
    }

    public static ClassStudentBanEntity fromModel(ClassStudentBan model) {
        return ClassStudentBanEntity.builder()
                .id(model.getId())
                .classId(model.getClassId())
                .studentId(model.getStudentId())
                .reason(model.getReason())
                .bannedBy(model.getBannedBy())
                .active(model.isActive())
                .build();
    }
}
