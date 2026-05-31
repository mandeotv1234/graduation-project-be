package graduation_project_be.adapter.web.api.dtos.response;

import java.util.List;
import java.util.Map;

import graduation_project_be.application.usecases.response.ExecuteSqlResponse;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TableMetadata;

public record ExecuteSqlResponseDto(
        List<Map<String, Object>> resultSet,
        List<String> columns,
        int rowCount,
        Integer executionTimeMs,
        String errorMessage,
        String statusMessage,
        List<TableMetadata> schema,
        List<RoutineMetadata> routines) {
    public static ExecuteSqlResponseDto fromResponse(ExecuteSqlResponse r) {
        return new ExecuteSqlResponseDto(
                r.resultSet(),
                r.columns(),
                r.rowCount(),
                r.executionTimeMs(),
                r.errorMessage(),
                r.statusMessage(),
                r.schema(),
                r.routines());
    }
}
