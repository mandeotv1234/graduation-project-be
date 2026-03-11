package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.SubmitExamRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SubmitExamRequestDto(
                @NotEmpty(message = "Answers list cannot be empty") @Valid List<AnswerItemDto> answers) {

        public record AnswerItemDto(
                        @NotNull(message = "Question ID is required") Long questionId,
                        String studentQuery) {
        }

        public SubmitExamRequest toRequest(Long examId) {
                List<SubmitExamRequest.AnswerItem> items = answers.stream()
                                .map(a -> new SubmitExamRequest.AnswerItem(a.questionId(), a.studentQuery()))
                                .toList();
                return new SubmitExamRequest(examId, items);
        }
}
