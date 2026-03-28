package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.GetExamTemplatesUsecase;

import java.time.LocalDateTime;

public record ExamTemplateListItemResponseDto(
        Long sourceExamId,
        Long latestTemplateId,
        Integer latestVersion,
        int versionCount,
        String title,
        String description,
        String sharedByName,
        int questionCount,
        LocalDateTime latestSharedAt) {

    public static ExamTemplateListItemResponseDto fromResponse(
            GetExamTemplatesUsecase.ExamTemplateListItem response) {
        return new ExamTemplateListItemResponseDto(
                response.sourceExamId(),
                response.latestTemplateId(),
                response.latestVersion(),
                response.versionCount(),
                response.title(),
                response.description(),
                response.sharedByName(),
                response.questionCount(),
                response.latestSharedAt());
    }
}
