package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.MicrosoftLoginRequest;
import jakarta.validation.constraints.NotBlank;

public record MicrosoftLoginRequestDto(
        @NotBlank(message = "ID token is required")
        String idToken
) {
    public MicrosoftLoginRequest toRequest() {
        return MicrosoftLoginRequest.builder()
                .idToken(idToken)
                .build();
    }
}
