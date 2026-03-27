package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GenerateGradingRubricRequest;

import java.util.List;

public record GenerateGradingRubricRequestDto(
        String correctQuery,
        String questionContent,
        Double totalPoints,
        String questionType,
        List<ContextQueryDto> contextQueries) {

    public record ContextQueryDto(
            String questionType,
            String content,
            String correctQuery) {
    }

    public GenerateGradingRubricRequest toRequest() {
        List<GenerateGradingRubricRequest.ContextQuery> context = contextQueries == null
                ? List.of()
                : contextQueries.stream()
                        .map(item -> new GenerateGradingRubricRequest.ContextQuery(
                                item.questionType(),
                                item.content(),
                                item.correctQuery()))
                        .toList();

        return new GenerateGradingRubricRequest(
                correctQuery == null ? "" : correctQuery,
                questionContent == null ? "" : questionContent,
                totalPoints == null ? 1.0 : totalPoints,
                questionType == null || questionType.isBlank() ? "CREATE_TABLE" : questionType,
                context);
    }
}
