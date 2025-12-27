package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.User;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record GetStudentsInClassResponse(
    Long id,
    String email,
    String fullName,
    LocalDateTime createdAt

) {
    public static GetStudentsInClassResponse fromModel(User user) {
        return GetStudentsInClassResponse.builder()
            .id(user.getId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .createdAt(user.getCreatedAt())
            .build();
    }
}
