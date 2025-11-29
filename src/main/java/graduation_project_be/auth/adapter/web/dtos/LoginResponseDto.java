package graduation_project_be.auth.adapter.web.dtos;

import graduation_project_be.auth.application.usecase.LoginUsecase.LoginResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response object containing the JWT access token")
public record LoginResponseDto(
        @Schema(description = "JWT access token for authenticating subsequent requests", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyMjEyMDIwMUBzdHVkZW50LmhjbXVzLmVkdS52biIsImlhdCI6MTY5OTQ2NDAwMCwiZXhwIjoxNjk5NTUwNDAwfQ.abcde...")
        String accessToken
) {
    public static LoginResponseDto fromResponse(LoginResponse loginResponse) {
        return new LoginResponseDto(loginResponse.accessToken());
    }
}