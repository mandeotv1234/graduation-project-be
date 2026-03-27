package graduation_project_be.adapter.web.api.dtos.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.usecases.request.TestGradeInsertRequest;

public record TestGradeInsertRequestDto(
        String correctQuery,
        String studentQuery,
        Object gradingRubric,
        Double totalPoints) {

    public TestGradeInsertRequest toRequest(Long examId, ObjectMapper objectMapper) {
        String rubric = toRubricJson(gradingRubric, objectMapper);
        return new TestGradeInsertRequest(
                examId,
                correctQuery == null ? "" : correctQuery,
                studentQuery == null ? "" : studentQuery,
                rubric,
                totalPoints == null ? 1.0 : totalPoints);
    }

    private static String toRubricJson(Object rubric, ObjectMapper objectMapper) {
        if (rubric == null) {
            return "";
        }
        if (rubric instanceof String rubricText) {
            return rubricText;
        }
        try {
            return objectMapper.valueToTree(rubric).toString();
        } catch (Exception ex) {
            return String.valueOf(rubric);
        }
    }
}
