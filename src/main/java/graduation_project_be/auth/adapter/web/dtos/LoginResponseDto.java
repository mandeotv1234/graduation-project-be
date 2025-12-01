package graduation_project_be.auth.adapter.web.dtos;

import graduation_project_be.auth.application.usecase.dtos.response.LoginResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "Response object containing the JWT tokens")
public record LoginResponseDto(
        @Schema(description = "JWT access token for authenticating subsequent requests", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyMjEyMDIwMUBzdHVkZW50LmhjbXVzLmVkdS52biIsImlhdCI6MTY5OTQ2NDAwMCwiZXhwIjoxNjk5NTUwNDAwfQ.abcde...")
        String accessToken,
        @Schema(description = "JWT refresh token for obtaining new access tokens", example = "eyJhbGciOiJIUzI1NiJ9...")
        String refreshToken
) {
    public static LoginResponseDto fromResponse(LoginResponse loginResponse) {
        return LoginResponseDto.builder()
                .accessToken(loginResponse.accessToken())
                .refreshToken(loginResponse.refreshToken())
                .build();
    }
}