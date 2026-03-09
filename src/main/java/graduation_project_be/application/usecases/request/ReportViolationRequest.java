package graduation_project_be.application.usecases.request;

public record ReportViolationRequest(
        Long examId,
        String violationType,
        String description,
        String ipAddress,
        String userAgent) {
}
