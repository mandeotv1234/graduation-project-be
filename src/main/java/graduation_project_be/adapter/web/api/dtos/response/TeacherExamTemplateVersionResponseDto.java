package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetTeacherExamTemplateVersionItemResponse;

import java.time.LocalDateTime;

public record TeacherExamTemplateVersionResponseDto(
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
    public static TeacherExamTemplateVersionResponseDto fromResponse(
            GetTeacherExamTemplateVersionItemResponse response) {
        return new TeacherExamTemplateVersionResponseDto(
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
