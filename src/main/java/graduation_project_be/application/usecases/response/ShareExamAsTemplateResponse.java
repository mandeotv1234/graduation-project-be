package graduation_project_be.application.usecases.response;

public record ShareExamAsTemplateResponse(Long templateId, Long sourceExamId, Integer version, int questionCount) {
}
