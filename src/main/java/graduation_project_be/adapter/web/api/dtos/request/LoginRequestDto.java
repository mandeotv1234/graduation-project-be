package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.LoginRequest;
import jakarta.validation.constraints.Pattern;

public record LoginRequestDto(
        @Pattern(
                regexp = "^[a-zA-Z0-9._%+-]+@(student|fit)\\.hcmus\\.edu\\.vn$",
                message = "Email must end with @student.hcmus.edu.vn or @fit.hcmus.edu.vn"
        )
        String email,

        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$",
                message = "Password must be at least 8 characters and include 1 uppercase letter, 1 number, and 1 special character"
        )
        String password
) {
    public LoginRequest toRequest() {
        return LoginRequest.builder()
                .email(email)
                .password(password)
                .build();
    }
}

