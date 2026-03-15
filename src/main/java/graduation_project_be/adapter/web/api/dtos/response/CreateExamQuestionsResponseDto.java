package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.CreateExamQuestionsResponse;

import java.util.List;

public record CreateExamQuestionsResponseDto(
        int totalCreated,
        List<ExamQuestionResponseDto> questions) {

    public static CreateExamQuestionsResponseDto fromResponse(CreateExamQuestionsResponse r) {
        List<ExamQuestionResponseDto> dtos = r.questions().stream()
                .map(ExamQuestionResponseDto::fromResponse)
                .toList();
        return new CreateExamQuestionsResponseDto(r.totalCreated(), dtos);
    }
}
