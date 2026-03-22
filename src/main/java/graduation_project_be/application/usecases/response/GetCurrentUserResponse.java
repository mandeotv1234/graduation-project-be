package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.User;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record GetCurrentUserResponse(
        Long id,
        String email,
        String fullName,
        String role,
        Boolean isActive,
        LocalDateTime createdAt
) {

    public static GetCurrentUserResponse fromModel(User user) {
        return GetCurrentUserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
