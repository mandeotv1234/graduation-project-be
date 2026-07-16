package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.CreateUserResponse;

import java.time.LocalDateTime;

public record CreateUserResponseDto(
        Long id,
        String email,
        String fullName,
        String role,
        Boolean isActive,
        LocalDateTime createdAt
) {
    public static CreateUserResponseDto fromResponse(CreateUserResponse response) {
        return new CreateUserResponseDto(
                response.id(),
                response.email(),
                response.fullName(),
                response.role(),
                response.isActive(),
                response.createdAt()
        );
    }
}
