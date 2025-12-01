package graduation_project_be.application.usecases.response;


import graduation_project_be.domain.models.Token;
import lombok.Builder;

@Builder
public record LoginResponse (
        String accessToken,
        String refreshToken
        ) {
    public static LoginResponse fromModel(Token token) {
        return LoginResponse.builder()
                .accessToken(token.getAccessToken())
                .refreshToken(token.getRefreshToken())
                .build();
    }
}
