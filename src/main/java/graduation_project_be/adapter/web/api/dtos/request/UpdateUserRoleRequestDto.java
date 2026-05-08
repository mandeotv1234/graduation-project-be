package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.UpdateUserRoleRequest;
import graduation_project_be.domain.models.enums.Role;
import jakarta.validation.constraints.NotBlank;

public record UpdateUserRoleRequestDto(
        @NotBlank(message = "role is required")
        String role
) {
    public UpdateUserRoleRequest toRequest(Long userId) {
        return new UpdateUserRoleRequest(userId, Role.valueOf(role.toUpperCase()));
    }
}
