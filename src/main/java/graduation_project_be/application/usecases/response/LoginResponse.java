package graduation_project_be.application.usecases.response;


import graduation_project_be.domain.models.Token;
import lombok.Builder;

import java.time.LocalDateTime;


@Builder
public record LoginResponse (
    String accessToken,
    String refreshToken,
    LocalDateTime accessTokenExpiresAt,
    LocalDateTime refreshTokenExpiresAt
    ) {
    public static LoginResponse fromModel(Token token) {
        return LoginResponse.builder()
                .accessToken(token.getAccessToken())
                .refreshToken(token.getRefreshToken())
                .accessTokenExpiresAt(token.getAccessTokenExpiresAt())
                .refreshTokenExpiresAt(token.getRefreshTokenExpiresAt())
                .build();
    }
}
