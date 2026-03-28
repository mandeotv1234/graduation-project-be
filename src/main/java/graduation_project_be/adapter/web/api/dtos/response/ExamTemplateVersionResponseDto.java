package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.GetExamTemplateVersionsUsecase;

import java.time.LocalDateTime;

public record ExamTemplateVersionResponseDto(
        Long templateId,
        Long sourceExamId,
        Integer version,
        String title,
        String description,
        String sharedByName,
        int questionCount,
        LocalDateTime createdAt,
        boolean isVisible
) {
    public static ExamTemplateVersionResponseDto fromResponse(
            GetExamTemplateVersionsUsecase.ExamTemplateVersionItem response) {
        return new ExamTemplateVersionResponseDto(
                response.templateId(),
                response.sourceExamId(),
                response.version(),
                response.title(),
                response.description(),
                response.sharedByName(),
                response.questionCount(),
                response.createdAt(),
                response.isVisible()
        );
    }
}
