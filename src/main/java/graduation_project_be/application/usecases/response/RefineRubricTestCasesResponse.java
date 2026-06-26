package graduation_project_be.application.usecases.response;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record RefineRubricTestCasesResponse(
        JsonNode rubric,
        List<String> changeSummary,
        List<String> warnings) {
}
