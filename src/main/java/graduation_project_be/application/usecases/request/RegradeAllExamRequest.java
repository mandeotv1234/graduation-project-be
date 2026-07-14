package graduation_project_be.application.usecases.request;

import graduation_project_be.domain.models.enums.RegradeAllScope;

public record RegradeAllExamRequest(
        Long examId,
        RegradeAllScope scope,
        Integer attemptNumber
) {
}
