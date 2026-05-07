package graduation_project_be.application.usecases.request;

import graduation_project_be.domain.models.enums.Role;

public record UpdateUserRoleRequest(Long userId, Role role) {}
