package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetClassesResponse;

import java.time.LocalDateTime;

public record GetClassesResponseDto(
        Long id,
        String classCode,
        Long creatorId,
        String semester,
        LocalDateTime createdAt,
        LocalDateTime deletedAt) {
    public static GetClassesResponseDto fromResponse(GetClassesResponse response) {
        return new GetClassesResponseDto(
                response.id(),
                response.classCode(),
                response.creatorId(),
                response.semester(),
                response.createdAt(),
                response.deletedAt());
    }
}
