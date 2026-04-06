package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.SaveExamDraftRequest;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SaveExamDraftRequestDto(
        @NotNull List<DraftAnswerItemDto> answers,
        String clientTimestamp
) {
    public record DraftAnswerItemDto(
            @NotNull Long questionId,
            String content
    ) {}

    public SaveExamDraftRequest toRequest(Long examId) {
        List<SaveExamDraftRequest.DraftAnswerItem> items = answers == null
                ? List.of()
                : answers.stream()
                        .map(a -> new SaveExamDraftRequest.DraftAnswerItem(a.questionId(), a.content()))
                        .toList();
        return new SaveExamDraftRequest(examId, items, clientTimestamp);
    }
}
