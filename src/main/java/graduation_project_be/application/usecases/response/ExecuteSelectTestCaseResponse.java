package graduation_project_be.application.usecases.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ExecuteSelectTestCaseResponse(
        @JsonProperty("columns_config") List<ColumnConfig> columnsConfig,
        List<List<String>> rows) {

    public record ColumnConfig(
            @JsonProperty("column_name") String columnName) {
    }
}
