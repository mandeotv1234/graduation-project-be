package graduation_project_be.application.usecases.request;

import lombok.Builder;

@Builder

public record RefreshTokenRequest(
        String refreshToken
) {
}
