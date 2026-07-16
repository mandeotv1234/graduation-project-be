package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateUserRequest;
import graduation_project_be.domain.models.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateUserRequestDto(
        @NotNull(message = "role is required")
        Role role,
        @NotBlank(message = "email is required")
        @Email(message = "email is invalid")
        @Pattern(
                regexp = "(?i)^[^@\\s]+@fit\\.hcmus\\.edu\\.vn$",
                message = "email must belong to @fit.hcmus.edu.vn"
        )
        String email
) {
    public CreateUserRequest toRequest() {
        return new CreateUserRequest(role, email);
    }
}
