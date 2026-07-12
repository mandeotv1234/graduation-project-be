package graduation_project_be.adapter.web.api.dtos.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.usecases.request.GenerateGradingRubricRequest;
import graduation_project_be.application.usecases.request.RubricAgentRunRequest;

import java.util.List;

public record RubricAgentRunRequestDto(
        Long examId,
        String correctQuery,
        String questionContent,
        Double totalPoints,
        String questionType,
        String schemaContext,
        List<GenerateGradingRubricRequestDto.ContextQueryDto> contextQueries,
        Object currentRubric,
        String teacherInstruction) {

    public RubricAgentRunRequest toRequest(ObjectMapper objectMapper) {
        List<GenerateGradingRubricRequest.ContextQuery> context = contextQueries == null
                ? List.of()
                : contextQueries.stream()
                        .map(item -> new GenerateGradingRubricRequest.ContextQuery(
                                item.questionType(),
                                item.content(),
                                item.correctQuery()))
                        .toList();

        return new RubricAgentRunRequest(
                examId,
                correctQuery == null ? "" : correctQuery,
                questionContent == null ? "" : questionContent,
                totalPoints == null ? 1.0 : totalPoints,
                questionType == null || questionType.isBlank() ? "CREATE_TABLE" : questionType,
                schemaContext == null ? "" : schemaContext,
                context,
                toRubricJson(currentRubric, objectMapper),
                teacherInstruction == null ? "" : teacherInstruction);
    }

    private static String toRubricJson(Object rubric, ObjectMapper objectMapper) {
        if (rubric == null) {
            return "{}";
        }
        if (rubric instanceof String rubricText) {
            return rubricText.isBlank() ? "{}" : rubricText;
        }
        try {
            return objectMapper.valueToTree(rubric).toString();
        } catch (Exception ex) {
            return String.valueOf(rubric);
        }
    }
}
