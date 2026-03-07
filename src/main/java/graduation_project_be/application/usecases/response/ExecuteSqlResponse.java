package graduation_project_be.application.usecases.response;

import java.util.List;
import java.util.Map;

public record ExecuteSqlResponse(
        List<Map<String, Object>> resultSet,
        int rowCount,
        Integer executionTimeMs,
        String errorMessage) {
    public static ExecuteSqlResponse success(List<Map<String, Object>> resultSet, int executionTimeMs) {
        return new ExecuteSqlResponse(resultSet, resultSet.size(), executionTimeMs, null);
    }

    public static ExecuteSqlResponse error(String errorMessage) {
        return new ExecuteSqlResponse(List.of(), 0, null, errorMessage);
    }
}
