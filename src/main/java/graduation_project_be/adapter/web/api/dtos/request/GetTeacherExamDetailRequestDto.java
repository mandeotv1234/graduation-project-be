package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetTeacherExamDetailRequest;

public record GetTeacherExamDetailRequestDto(
        Long examId) {
    public GetTeacherExamDetailRequest toRequest() {
        return new GetTeacherExamDetailRequest(examId);
    }
}