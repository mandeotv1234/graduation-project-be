package graduation_project_be.application.usecases.request;

import java.util.List;

public record PreviewSubmitRequest(Long examId, List<AnswerItem> answers) {
    public record AnswerItem(Long questionId, String studentQuery) {}
}
