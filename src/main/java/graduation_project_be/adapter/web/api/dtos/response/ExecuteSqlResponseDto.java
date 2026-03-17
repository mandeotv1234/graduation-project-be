package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.ExecuteSqlResponse;
import graduation_project_be.domain.models.TableMetadata;
import java.util.List;
import java.util.Map;

public record ExecuteSqlResponseDto(
        List<Map<String, Object>> resultSet,
        int rowCount,
        Integer executionTimeMs,
        String errorMessage,
        List<TableMetadata> schema) {
    public static ExecuteSqlResponseDto fromResponse(ExecuteSqlResponse r) {
        return new ExecuteSqlResponseDto(
                r.resultSet(),
                r.rowCount(),
                r.executionTimeMs(),
                r.errorMessage(),
                r.schema());
    }
}
