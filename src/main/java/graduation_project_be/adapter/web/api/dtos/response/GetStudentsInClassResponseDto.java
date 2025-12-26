package graduation_project_be.adapter.web.api.dtos.response;
import graduation_project_be.application.usecases.response.GetStudentsInClassResponse;

import java.time.LocalDateTime;

public record GetStudentsInClassResponseDto(
        Long id,
        String email,
        String fullName,
        LocalDateTime createdAt
) {
    public static GetStudentsInClassResponseDto fromResponse(
            GetStudentsInClassResponse response) {
        return new GetStudentsInClassResponseDto(
                response.id(),
                response.email(),
                response.fullName(),
                response.createdAt());
    }
}
