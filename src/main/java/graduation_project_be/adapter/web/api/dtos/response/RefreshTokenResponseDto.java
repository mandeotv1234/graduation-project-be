package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.RefreshTokenResponse;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record RefreshTokenResponseDto(
        String accessToken,
        LocalDateTime expiresAt
) {
    public static RefreshTokenResponseDto fromResponse(RefreshTokenResponse refreshTokenResponse) {
        return RefreshTokenResponseDto.builder()
                .accessToken(refreshTokenResponse.accessToken())
                .expiresAt(refreshTokenResponse.expiresAt())
                .build();
    }
}