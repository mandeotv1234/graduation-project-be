package graduation_project_be.adapter.web.api.dtos.request;

import jakarta.validation.constraints.NotNull;

public record ShareExamAsTemplateRequestDto(
        @NotNull Long examId
) {
}
