package graduation_project_be.application.usecases.response;


import lombok.Builder;

@Builder
public record LoginResponse (
        String accessToken,
        String refreshToken
        ) {
    public static LoginResponse fromTokens(String accessToken, String refreshToken) {
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }
}
