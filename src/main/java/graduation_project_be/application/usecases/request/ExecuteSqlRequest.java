package graduation_project_be.application.usecases.request;

public record ExecuteSqlRequest(Long examId, String sql, String ipAddress, String userAgent) {
}
