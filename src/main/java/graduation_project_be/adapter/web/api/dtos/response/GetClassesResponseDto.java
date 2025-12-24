package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetClassesResponse;

import java.time.LocalDateTime;

public record GetClassesResponseDto(
        Long id,
        String classCode,
        Long teacherId,
        String semester,
        LocalDateTime createdAt) {
    public static GetClassesResponseDto fromResponse(GetClassesResponse response) {
        return new GetClassesResponseDto(
                response.id(),
                response.classCode(),
                response.teacherId(),
                response.semester(),
                response.createdAt());
    }
}
