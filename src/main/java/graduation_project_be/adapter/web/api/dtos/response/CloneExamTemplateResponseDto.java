package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.CloneExamTemplateUsecase;

public record CloneExamTemplateResponseDto(
        Long examId,
        String title,
        int questionCount) {

    public static CloneExamTemplateResponseDto fromResponse(CloneExamTemplateUsecase.CloneResult response) {
        return new CloneExamTemplateResponseDto(
                response.examId(),
                response.title(),
                response.questionCount());
    }
}
