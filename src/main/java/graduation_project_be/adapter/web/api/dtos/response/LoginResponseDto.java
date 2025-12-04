package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.LoginResponse;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record LoginResponseDto(
        String accessToken,
        LocalDateTime expiresAt
) {
    public static LoginResponseDto fromResponse(LoginResponse loginResponse) {
        return LoginResponseDto.builder()
                .accessToken(loginResponse.accessToken())
                .expiresAt(loginResponse.expiresAt())
                .build();
    }
}