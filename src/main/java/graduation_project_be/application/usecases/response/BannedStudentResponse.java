package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.ClassStudentBan;

import java.time.LocalDateTime;

public record BannedStudentResponse(
        Long id,
        Long studentId,
        String email,
        String fullName,
        String reason,
        String bannedByName,
        LocalDateTime bannedAt
) {
    public static BannedStudentResponse fromModel(ClassStudentBan ban, String email, String fullName, String bannedByName) {
        return new BannedStudentResponse(
                ban.getId(),
                ban.getStudentId(),
                email,
                fullName,
                ban.getReason(),
                bannedByName,
                ban.getBannedAt()
        );
    }
}
