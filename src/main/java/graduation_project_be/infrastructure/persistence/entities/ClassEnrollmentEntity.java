package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ClassEnrollment;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table(name = "class_enrollments")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(ClassEnrollmentId.class)
public class ClassEnrollmentEntity {

    @Id
    @Column(name = "class_id")
    private Long classId;

    @Id
    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    public ClassEnrollment toModel() {
        return ClassEnrollment.builder()
                .classId(classId)
                .studentId(studentId)
                .joinedAt(joinedAt)
                .build();
    }

    public static ClassEnrollmentEntity fromModel(ClassEnrollment model) {
        return ClassEnrollmentEntity.builder()
                .classId(model.getClassId())
                .studentId(model.getStudentId())
                .joinedAt(model.getJoinedAt())
                .build();
    }
}
