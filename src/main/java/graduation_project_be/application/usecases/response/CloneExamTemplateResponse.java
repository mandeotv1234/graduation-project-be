package graduation_project_be.application.usecases.response;

public record CloneExamTemplateResponse(Long examId, String title, int questionCount) {
}
