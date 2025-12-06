package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.LoginResponse;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record LoginResponseDto(
        String accessToken,
    String refreshToken,
    LocalDateTime accessTokenExpiresAt,
    LocalDateTime refreshTokenExpiresAt
) {
    public static LoginResponseDto fromResponse(LoginResponse loginResponse) {
        return LoginResponseDto.builder()
                .accessToken(loginResponse.accessToken())
        .refreshToken(loginResponse.refreshToken())
        .accessTokenExpiresAt(loginResponse.accessTokenExpiresAt())
        .refreshTokenExpiresAt(loginResponse.refreshTokenExpiresAt())
                .build();
    }
}