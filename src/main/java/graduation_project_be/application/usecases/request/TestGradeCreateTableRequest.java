package graduation_project_be.application.usecases.request;

public record TestGradeCreateTableRequest(
        String correctQuery,
        String studentQuery,
        String gradingRubric,
        double totalPoints) {
}
