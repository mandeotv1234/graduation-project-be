package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GoogleLoginRequest;
import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequestDto(
        @NotBlank(message = "Authorization code is required")
        String code,

        @NotBlank(message = "Redirect URI is required")
        String redirectUri,

        Boolean rememberMe
) {
    public GoogleLoginRequest toRequest() {
        return GoogleLoginRequest.builder()
                .code(code)
                .redirectUri(redirectUri)
                .rememberMe(rememberMe != null ? rememberMe : false)
                .build();
    }
}
