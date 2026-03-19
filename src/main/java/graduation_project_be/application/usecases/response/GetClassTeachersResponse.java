package graduation_project_be.application.usecases.response;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record GetClassTeachersResponse(
        Long id,
        String email,
        String fullName,
        LocalDateTime addedAt,
        boolean isCreator) {
}
