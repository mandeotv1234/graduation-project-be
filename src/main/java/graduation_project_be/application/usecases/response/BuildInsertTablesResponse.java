package graduation_project_be.application.usecases.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record BuildInsertTablesResponse(
        List<InsertTableConfig> tables,
        int preparedCount,
        int targetTableCount) {

    public record InsertTableConfig(
            @JsonProperty("table_name") String tableName,
            @JsonProperty("row_grading_strategy") String rowGradingStrategy,
            @JsonProperty("columns_config") List<InsertColumnConfig> columnsConfig,
            @JsonProperty("expected_data") List<Map<String, Object>> expectedData) {
    }

    public record InsertColumnConfig(
            String name,
            @JsonProperty("is_primary_key") boolean isPrimaryKey,
            @JsonProperty("is_graded") boolean isGraded,
            @JsonProperty("match_type") String matchType) {
    }
}
