package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.TeacherClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table(name = "teacher_classes")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(TeacherClassId.class)
public class TeacherClassEntity {

    @Id
    @Column(name = "class_id")
    private Long classId;

    @Id
    @Column(name = "teacher_id")
    private Long teacherId;

    @Column(name = "added_at")
    private LocalDateTime addedAt;

    public TeacherClass toModel() {
        return TeacherClass.builder()
                .classId(classId)
                .teacherId(teacherId)
                .addedAt(addedAt)
                .build();
    }

    public static TeacherClassEntity fromModel(TeacherClass model) {
        return TeacherClassEntity.builder()
                .classId(model.getClassId())
                .teacherId(model.getTeacherId())
                .addedAt(model.getAddedAt())
                .build();
    }
}
