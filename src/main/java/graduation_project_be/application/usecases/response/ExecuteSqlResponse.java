package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.TableMetadata;
import java.util.List;
import java.util.Map;

public record ExecuteSqlResponse(
        List<Map<String, Object>> resultSet,
        int rowCount,
        Integer executionTimeMs,
        String errorMessage,
        List<TableMetadata> schema) {
    public static ExecuteSqlResponse success(
            List<Map<String, Object>> resultSet,
            int executionTimeMs,
            List<TableMetadata> schema) {
        return new ExecuteSqlResponse(resultSet, resultSet.size(), executionTimeMs, null, schema);
    }

    public static ExecuteSqlResponse error(String errorMessage) {
        return new ExecuteSqlResponse(List.of(), 0, null, errorMessage, null);
    }
}
