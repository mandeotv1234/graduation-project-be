package graduation_project_be.adapter.web.api.dtos.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.usecases.request.TestGradeTriggerRequest;

public record TestGradeTriggerRequestDto(
        String correctQuery,
        String studentQuery,
        Object gradingRubric,
        Double totalPoints) {

    public TestGradeTriggerRequest toRequest(Long examId, ObjectMapper objectMapper) {
        String rubric = toRubricJson(gradingRubric, objectMapper);
        return new TestGradeTriggerRequest(
                examId,
                correctQuery == null ? "" : correctQuery,
                studentQuery == null ? "" : studentQuery,
                rubric,
                totalPoints == null ? 1.0 : totalPoints.doubleValue());
    }

    private static String toRubricJson(Object gradingRubric, ObjectMapper objectMapper) {
        if (gradingRubric == null) {
            return "";
        }
        if (gradingRubric instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(gradingRubric);
        } catch (Exception e) {
            return gradingRubric.toString();
        }
    }
}
