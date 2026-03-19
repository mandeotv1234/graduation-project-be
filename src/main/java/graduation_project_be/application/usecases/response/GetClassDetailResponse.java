package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Class;
import lombok.Builder;
import java.time.LocalDateTime;

@Builder
public record GetClassDetailResponse(
        Long id,
        String classCode,
        Long creatorId,
        String semester,
        LocalDateTime createdAt) {
    public static GetClassDetailResponse fromModel(Class clazz) {
        return GetClassDetailResponse.builder()
                .id(clazz.getId())
                .classCode(clazz.getClassCode())
                .creatorId(clazz.getCreatorId())
                .semester(clazz.getSemester())
                .createdAt(clazz.getCreatedAt())
                .build();
    }
}
