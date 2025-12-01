package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.LoginResponse;
import lombok.Builder;

@Builder
public record LoginResponseDto(
        String accessToken,
        String refreshToken
) {
    public static LoginResponseDto fromResponse(LoginResponse loginResponse) {
        return LoginResponseDto.builder()
                .accessToken(loginResponse.accessToken())
                .refreshToken(loginResponse.refreshToken())
                .build();
    }
}