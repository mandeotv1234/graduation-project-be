package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.User;

public record UpdateUserRoleResponse(
        Long id,
        String email,
        String fullName,
        String role
) {
    public static UpdateUserRoleResponse fromModel(User user) {
        return new UpdateUserRoleResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name()
        );
    }
}
