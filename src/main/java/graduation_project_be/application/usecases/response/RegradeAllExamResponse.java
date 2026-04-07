package graduation_project_be.application.usecases.response;

public record RegradeAllExamResponse(
    int queuedCount,
    int skippedCount,
    String message
) {}
