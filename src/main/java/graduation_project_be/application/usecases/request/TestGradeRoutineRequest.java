package graduation_project_be.application.usecases.request;

public record TestGradeRoutineRequest(
        Long examId,
        String correctQuery,
        String studentQuery,
        String gradingRubric,
        double totalPoints) {
}
