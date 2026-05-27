package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.TeacherExecuteSqlOnResultRequest;
import jakarta.validation.constraints.NotBlank;

public record TeacherExecuteSqlOnResultRequestDto(
        @NotBlank String sql) {

    public TeacherExecuteSqlOnResultRequest toRequest(Long examId, Long resultId) {
        return new TeacherExecuteSqlOnResultRequest(examId, resultId, sql);
    }
}
