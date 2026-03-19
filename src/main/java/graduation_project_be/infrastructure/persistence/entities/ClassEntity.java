package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.Class;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table(name = "classes")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_code", unique = true, nullable = false)
    private String classCode;

    @Column(name = "creator_id")
    private Long creatorId;

    private String semester;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Class toModel() {
        return Class.builder()
                .id(id)
                .classCode(classCode)
                .creatorId(creatorId)
                .semester(semester)
                .createdAt(createdAt)
                .build();
    }

    public static ClassEntity fromModel(Class classModel) {
        return ClassEntity.builder()
                .id(classModel.getId())
                .classCode(classModel.getClassCode())
                .creatorId(classModel.getCreatorId())
                .semester(classModel.getSemester())
                .createdAt(classModel.getCreatedAt())
                .build();
    }
}
