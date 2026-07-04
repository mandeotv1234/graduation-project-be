package graduation_project_be.application.usecases.request;

import java.util.List;

public record RubricAgentRunRequest(
        Long examId,
        String correctQuery,
        String questionContent,
        double totalPoints,
        String questionType,
        String schemaContext,
        List<GenerateGradingRubricRequest.ContextQuery> contextQueries,
        String currentRubricJson,
        String teacherInstruction) {
}
