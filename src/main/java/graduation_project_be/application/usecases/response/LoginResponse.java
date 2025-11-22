package graduation_project_be.application.usecases.response;


import lombok.Builder;

@Builder
public record LoginResponse (
        String accessToken
        ) {
    public static LoginResponse fromAccessToken(String accessToken) {
        return LoginResponse.builder()
                .accessToken(accessToken)
                .build();
    }
}
