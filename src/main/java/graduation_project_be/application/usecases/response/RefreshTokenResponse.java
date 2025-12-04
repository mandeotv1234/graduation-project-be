package graduation_project_be.application.usecases.response;


import graduation_project_be.domain.models.Token;
import lombok.Builder;

import java.time.LocalDateTime;


@Builder
public record RefreshTokenResponse(
        String accessToken,
        String refreshToken,
        LocalDateTime expiresAt
        ) {
    public static RefreshTokenResponse fromModel(Token token) {
        return RefreshTokenResponse.builder()
                .accessToken(token.getAccessToken())
                .refreshToken(token.getRefreshToken())
                .expiresAt(token.getExpiresAt())
                .build();
    }
}
