package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Class;
import lombok.Builder;

@Builder
public record CreateClassResponse(
    Long id,
    String classCode,
    String semester,
    Long teacherId,
    java.time.LocalDateTime createdAt
) {
    public static CreateClassResponse fromModel(Class clazz) {
        return CreateClassResponse.builder()
                .id(clazz.getId())
                .classCode(clazz.getClassCode())
                .semester(clazz.getSemester())
                .teacherId(clazz.getTeacherId())
                .createdAt(clazz.getCreatedAt())
                .build();
    }
}
