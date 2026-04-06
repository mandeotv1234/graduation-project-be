package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.ExecuteSqlRequest;
import jakarta.validation.constraints.NotBlank;

public record ExecuteSqlRequestDto(
        @NotBlank(message = "SQL query is required") String sql) {
    public ExecuteSqlRequest toRequest(Long examId, String ipAddress, String userAgent) {
        return new ExecuteSqlRequest(examId, sql, ipAddress, userAgent);
    }
}
