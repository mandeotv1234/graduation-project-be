package graduation_project_be.auth.adapter.web.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Request object for user login")
public record LoginRequestDto(
        @Schema(description = "User's email address. Must be a student email.", example = "22120201@student.hcmus.edu.vn")
        @Pattern(
                regexp = "^[a-zA-Z0-9._%+-]+@student\\.hcmus\\.edu\\.vn$",
                message = "Email must end with @student.hcmus.edu.vn"
        )
        String email,

        @Schema(description = "User's password. Must be at least 8 characters, include 1 uppercase letter, 1 number, and 1 special character.", example = "Password@123")
        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$",
                message = "Password must be at least 8 characters and include 1 uppercase letter, 1 number, and 1 special character"
        )
        String password
) {
}
