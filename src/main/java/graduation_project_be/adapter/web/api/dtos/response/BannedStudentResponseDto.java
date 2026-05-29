package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.BannedStudentResponse;

import java.time.LocalDateTime;

public record BannedStudentResponseDto(
        Long id,
        Long studentId,
        String email,
        String fullName,
        String reason,
        String bannedByName,
        LocalDateTime bannedAt
) {
    public static BannedStudentResponseDto fromResponse(BannedStudentResponse response) {
        return new BannedStudentResponseDto(
                response.id(),
                response.studentId(),
                response.email(),
                response.fullName(),
                response.reason(),
                response.bannedByName(),
                response.bannedAt()
        );
    }
}
