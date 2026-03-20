package graduation_project_be.application.usecases.request;

import lombok.Builder;

@Builder
public record GoogleLoginRequest(
        String code,
        String redirectUri,
        Boolean rememberMe
) {
}
