package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.PreviewSubmitRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PreviewSubmitRequestDto(
        @NotEmpty(message = "Answers list cannot be empty") @Valid List<AnswerItemDto> answers) {

    public record AnswerItemDto(
            @NotNull(message = "Question ID is required") Long questionId,
            String studentQuery) {}

    public PreviewSubmitRequest toRequest(Long examId) {
        List<PreviewSubmitRequest.AnswerItem> items = answers.stream()
                .map(a -> new PreviewSubmitRequest.AnswerItem(a.questionId(), a.studentQuery()))
                .toList();
        return new PreviewSubmitRequest(examId, items);
    }
}
