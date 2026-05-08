package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.UpdateUserRoleResponse;

public record UpdateUserRoleResponseDto(
        Long id,
        String email,
        String fullName,
        String role
) {
    public static UpdateUserRoleResponseDto fromResponse(UpdateUserRoleResponse response) {
        return new UpdateUserRoleResponseDto(
                response.id(),
                response.email(),
                response.fullName(),
                response.role()
        );
    }
}
