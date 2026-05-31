package graduation_project_be.application.usecases.response;

import java.util.List;
import java.util.Map;

import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TableMetadata;

public record ExecuteSqlResponse(
        List<Map<String, Object>> resultSet,
        List<String> columns,
        int rowCount,
        Integer executionTimeMs,
        String errorMessage,
        String statusMessage,
        List<TableMetadata> schema,
        List<RoutineMetadata> routines) {
    public static ExecuteSqlResponse success(
            List<Map<String, Object>> resultSet,
            List<String> columns,
            int rowCount,
            int executionTimeMs,
            String statusMessage,
            List<TableMetadata> schema,
            List<RoutineMetadata> routines) {
        return new ExecuteSqlResponse(resultSet, columns, rowCount, executionTimeMs, null, statusMessage, schema, routines);
    }

    public static ExecuteSqlResponse error(String errorMessage) {
        return new ExecuteSqlResponse(List.of(), List.of(), 0, null, errorMessage, null, null, null);
    }
}
