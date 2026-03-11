package graduation_project_be.application.usecases.request;

public record StartExamSessionRequest(
        Long examId,
        String ipAddress,
        String userAgent) {
}
