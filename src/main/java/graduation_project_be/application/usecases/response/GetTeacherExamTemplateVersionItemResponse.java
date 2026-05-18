package graduation_project_be.application.usecases.response;

import java.time.LocalDateTime;

public record GetTeacherExamTemplateVersionItemResponse(
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
}
