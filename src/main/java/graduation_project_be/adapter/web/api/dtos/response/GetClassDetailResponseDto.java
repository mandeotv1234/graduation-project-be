package graduation_project_be.adapter.web.api.dtos.response;

import java.time.LocalDateTime;

import graduation_project_be.application.usecases.response.GetClassDetailResponse;

public record GetClassDetailResponseDto(
        Long id,
        String classCode,
        Long creatorId,
        String semester,
        LocalDateTime createdAt,
        LocalDateTime deletedAt) {
    public static GetClassDetailResponseDto fromResponse(
            GetClassDetailResponse response) {
        return new GetClassDetailResponseDto(
                response.id(),
                response.classCode(),
                response.creatorId(),
                response.semester(),
                response.createdAt(),
                response.deletedAt());
    }
}
