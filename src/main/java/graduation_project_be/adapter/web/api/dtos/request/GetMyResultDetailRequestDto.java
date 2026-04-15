package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetMyResultDetailRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;

@Builder
public record GetMyResultDetailRequestDto(
        @NotNull(message = "Result ID is required") @Positive(message = "Result ID must be positive") Long resultId) {
    public GetMyResultDetailRequest toRequest() {
        return GetMyResultDetailRequest.builder()
                .resultId(resultId)
                .build();
    }
}
