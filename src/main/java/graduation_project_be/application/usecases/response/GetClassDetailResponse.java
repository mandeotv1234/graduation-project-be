package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Class;
import lombok.Builder;
import java.time.LocalDateTime;

@Builder
public record GetClassDetailResponse(
        Long id,
        String classCode,
        Long teacherId,
        String semester,
        LocalDateTime createdAt) {
    public static GetClassDetailResponse fromModel(Class clazz) {
        return GetClassDetailResponse.builder()
                .id(clazz.getId())
                .classCode(clazz.getClassCode())
                .teacherId(clazz.getTeacherId())
                .semester(clazz.getSemester())
                .createdAt(clazz.getCreatedAt())
                .build();
    }
}