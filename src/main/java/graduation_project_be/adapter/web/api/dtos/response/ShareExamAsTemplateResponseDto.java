package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.ShareExamAsTemplateUsecase;

public record ShareExamAsTemplateResponseDto(
        Long templateId,
        Long sourceExamId,
        Integer version,
        int questionCount) {

    public static ShareExamAsTemplateResponseDto fromResponse(
            ShareExamAsTemplateUsecase.ShareResult response) {
        return new ShareExamAsTemplateResponseDto(
                response.templateId(),
                response.sourceExamId(),
                response.version(),
                response.questionCount());
    }
}
