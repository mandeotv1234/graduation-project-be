package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.User;

import java.time.LocalDateTime;

public record GetUsersResponse(
        Long id,
        String email,
        String fullName,
        String role,
        Boolean isActive,
        LocalDateTime createdAt
) {
    public static GetUsersResponse fromModel(User user) {
        return new GetUsersResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                user.getIsActive(),
                user.getCreatedAt()
        );
    }
}
