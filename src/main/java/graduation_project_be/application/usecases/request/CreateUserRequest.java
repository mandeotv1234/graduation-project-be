package graduation_project_be.application.usecases.request;

import graduation_project_be.domain.models.enums.Role;

public record CreateUserRequest(
        Role role,
        String email
) {
}
