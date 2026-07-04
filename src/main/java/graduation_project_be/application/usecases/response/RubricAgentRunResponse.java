package graduation_project_be.application.usecases.response;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record RubricAgentRunResponse(
        JsonNode rubric,
        List<RubricAgentStep> steps,
        List<RubricAgentFinding> findings,
        List<String> plan,
        List<String> changeSummary,
        List<String> fixes,
        List<String> testCaseChanges,
        List<String> warnings,
        int iterations,
        boolean changed,
        double confidence) {

    public record RubricAgentStep(
            String tool,
            String status,
            String message) {
    }

    public record RubricAgentFinding(
            String severity,
            String code,
            String message) {
    }
}
