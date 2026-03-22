package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetCurrentUserResponse;

import java.time.LocalDateTime;

public record GetCurrentUserResponseDto(
        Long id,
        String email,
        String fullName,
        String role,
        Boolean isActive,
        LocalDateTime createdAt
) {

    public static GetCurrentUserResponseDto fromResponse(GetCurrentUserResponse response) {
        return new GetCurrentUserResponseDto(
                response.id(),
                response.email(),
                response.fullName(),
                response.role(),
                response.isActive(),
                response.createdAt());
    }
}
