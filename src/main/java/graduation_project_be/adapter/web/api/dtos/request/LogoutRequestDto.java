package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.LogoutRequest;
import jakarta.validation.constraints.NotBlank;

public record LogoutRequestDto(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {
    public LogoutRequest toRequest() {
        return LogoutRequest.builder()
                .refreshToken(refreshToken)
                .build();
    }
}
