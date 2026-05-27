package graduation_project_be.application.usecases.request;

public record TeacherExecuteSqlOnResultRequest(Long examId, Long resultId, String sql) {}
