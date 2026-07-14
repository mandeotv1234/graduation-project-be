package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.RegradeAllExamRequest;
import graduation_project_be.domain.models.enums.RegradeAllScope;
import jakarta.validation.constraints.Positive;

public record RegradeAllExamRequestDto(
        RegradeAllScope scope,
        @Positive(message = "Attempt number must be positive") Integer attemptNumber
) {

    public RegradeAllExamRequest toRequest(Long examId) {
        RegradeAllScope safeScope = scope != null ? scope : RegradeAllScope.ALL_ATTEMPTS;
        return new RegradeAllExamRequest(examId, safeScope, attemptNumber);
    }
}
