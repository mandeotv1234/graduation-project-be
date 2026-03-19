package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.CreateClassResponse;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record CreateClassResponseDto(
        Long id,
        String classCode,
        String semester,
        Long creatorId,
        LocalDateTime createdAt) {
    public static CreateClassResponseDto fromResponse(CreateClassResponse response) {
        return CreateClassResponseDto.builder()
                .id(response.id())
                .classCode(response.classCode())
                .semester(response.semester())
                .creatorId(response.creatorId())
                .createdAt(response.createdAt())
                .build();
    }
}
