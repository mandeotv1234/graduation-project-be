package graduation_project_be.application.usecases.request;

import java.util.List;

public record GenerateGradingRubricRequest(
        String correctQuery,
        String questionContent,
        double totalPoints,
        String questionType,
        String schemaContext,
        List<ContextQuery> contextQueries) {

    public record ContextQuery(
            String questionType,
            String content,
            String correctQuery) {
    }
}
