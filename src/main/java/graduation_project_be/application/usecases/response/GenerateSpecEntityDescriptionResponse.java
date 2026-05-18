package graduation_project_be.application.usecases.response;

/** Response from the generate-entity-description usecase. description may be null if Gemini failed. */
public record GenerateSpecEntityDescriptionResponse(
        Long entityId,
        String entityName,
        String description
) {}
