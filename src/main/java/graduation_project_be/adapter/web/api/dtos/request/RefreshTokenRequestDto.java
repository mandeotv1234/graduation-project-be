package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.RefreshTokenRequest;
import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequestDto(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {
        public RefreshTokenRequest toRequest() {
            return RefreshTokenRequest.builder()
                    .refreshToken(refreshToken)
                    .build();
        }
}
