package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetUsersResponse;

import java.time.LocalDateTime;

public record GetUsersResponseDto(
        Long id,
        String email,
        String fullName,
        String role,
        Boolean isActive,
        LocalDateTime createdAt
) {
    public static GetUsersResponseDto fromResponse(GetUsersResponse response) {
        return new GetUsersResponseDto(
                response.id(),
                response.email(),
                response.fullName(),
                response.role(),
                response.isActive(),
                response.createdAt()
        );
    }
}
