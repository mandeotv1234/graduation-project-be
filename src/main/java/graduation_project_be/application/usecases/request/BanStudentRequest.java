package graduation_project_be.application.usecases.request;

public record BanStudentRequest(
        Long classId,
        Long studentId,
        String reason
) {}
