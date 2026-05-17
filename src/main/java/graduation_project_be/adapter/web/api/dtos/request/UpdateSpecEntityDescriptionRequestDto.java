package graduation_project_be.adapter.web.api.dtos.request;

import jakarta.validation.constraints.Size;

public record UpdateSpecEntityDescriptionRequestDto(
        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description
) {}
