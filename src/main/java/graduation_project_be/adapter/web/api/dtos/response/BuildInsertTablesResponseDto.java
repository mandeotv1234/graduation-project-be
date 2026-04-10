package graduation_project_be.adapter.web.api.dtos.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import graduation_project_be.application.usecases.response.BuildInsertTablesResponse;

import java.util.List;
import java.util.Map;

public record BuildInsertTablesResponseDto(
        List<InsertTableConfigDto> tables,
        int preparedCount,
        int targetTableCount) {

    public static BuildInsertTablesResponseDto fromResponse(BuildInsertTablesResponse response) {
        List<InsertTableConfigDto> tables = response.tables().stream()
                .map(InsertTableConfigDto::fromResponse)
                .toList();

        return new BuildInsertTablesResponseDto(tables, response.preparedCount(), response.targetTableCount());
    }

    public record InsertTableConfigDto(
            @JsonProperty("table_name") String tableName,
            @JsonProperty("row_grading_strategy") String rowGradingStrategy,
            @JsonProperty("columns_config") List<InsertColumnConfigDto> columnsConfig,
            @JsonProperty("expected_data") List<Map<String, Object>> expectedData) {

        static InsertTableConfigDto fromResponse(BuildInsertTablesResponse.InsertTableConfig response) {
            List<InsertColumnConfigDto> columns = response.columnsConfig().stream()
                    .map(InsertColumnConfigDto::fromResponse)
                    .toList();

            return new InsertTableConfigDto(
                    response.tableName(),
                    response.rowGradingStrategy(),
                    columns,
                    response.expectedData());
        }
    }

    public record InsertColumnConfigDto(
            String name,
            @JsonProperty("is_primary_key") boolean isPrimaryKey,
            @JsonProperty("is_graded") boolean isGraded,
            @JsonProperty("match_type") String matchType) {

        static InsertColumnConfigDto fromResponse(BuildInsertTablesResponse.InsertColumnConfig response) {
            return new InsertColumnConfigDto(
                    response.name(),
                    response.isPrimaryKey(),
                    response.isGraded(),
                    response.matchType());
        }
    }
}
