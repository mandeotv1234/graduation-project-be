package graduation_project_be.application.usecases.response;


import graduation_project_be.domain.models.Token;
import lombok.Builder;

import java.time.LocalDateTime;


@Builder
public record RefreshTokenResponse(
    String accessToken,
    LocalDateTime accessTokenExpiresAt
    ) {
    public static RefreshTokenResponse fromModel(Token token) {
        return RefreshTokenResponse.builder()
                .accessToken(token.getAccessToken())
        .accessTokenExpiresAt(token.getAccessTokenExpiresAt())
                .build();
    }
}
