package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.CloneExamTemplateResponse;

public record CloneExamTemplateResponseDto(
        Long examId,
        String title,
        int questionCount) {

    public static CloneExamTemplateResponseDto fromResponse(CloneExamTemplateResponse response) {
        return new CloneExamTemplateResponseDto(
                response.examId(),
                response.title(),
                response.questionCount());
    }
}
