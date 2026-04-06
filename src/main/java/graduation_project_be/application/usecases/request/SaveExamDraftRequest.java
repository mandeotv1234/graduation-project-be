package graduation_project_be.application.usecases.request;

import java.util.List;

public record SaveExamDraftRequest(
        Long examId,
        List<DraftAnswerItem> answers,
        String clientTimestamp
) {
    public record DraftAnswerItem(
            Long questionId,
            String content
    ) {}
}
