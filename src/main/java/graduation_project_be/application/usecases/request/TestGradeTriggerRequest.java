package graduation_project_be.application.usecases.request;

public record TestGradeTriggerRequest(
        Long examId,
        String correctQuery,
        String studentQuery,
        String gradingRubric,
        double totalPoints) {
}
