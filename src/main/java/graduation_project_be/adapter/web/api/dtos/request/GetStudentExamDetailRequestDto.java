package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetStudentExamDetailRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;

@Builder
public record GetStudentExamDetailRequestDto(
        @NotNull(message = "Exam ID is required") @Positive(message = "Exam ID must be positive") Long examId) {
    public GetStudentExamDetailRequest toRequest() {
        return new GetStudentExamDetailRequest(examId);
    }
}
