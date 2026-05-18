package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.UpdateSpecEntityDescriptionRequest;
import jakarta.validation.constraints.Size;

public record UpdateSpecEntityDescriptionRequestDto(
        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description
) {
    public UpdateSpecEntityDescriptionRequest toRequest(
            Long specificationId,
            Long entityId,
            Long examId) {
        return new UpdateSpecEntityDescriptionRequest(specificationId, entityId, examId, description);
    }
}
