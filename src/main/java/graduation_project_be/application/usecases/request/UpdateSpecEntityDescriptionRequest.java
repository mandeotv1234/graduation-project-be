package graduation_project_be.application.usecases.request;

public record UpdateSpecEntityDescriptionRequest(
        Long specId,
        Long entityId,
        Long examId,
        String description
) {}
