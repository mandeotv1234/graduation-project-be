package graduation_project_be.adapter.web.api.dtos.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import graduation_project_be.application.usecases.response.ExecuteSelectTestCaseResponse;

import java.util.List;

public record ExecuteSelectTestCaseResponseDto(
        @JsonProperty("columns_config") List<ColumnConfigDto> columnsConfig,
        List<List<String>> rows) {

    public static ExecuteSelectTestCaseResponseDto fromResponse(ExecuteSelectTestCaseResponse response) {
        List<ColumnConfigDto> columns = response.columnsConfig().stream()
                .map(ColumnConfigDto::fromResponse)
                .toList();

        return new ExecuteSelectTestCaseResponseDto(columns, response.rows());
    }

    public record ColumnConfigDto(
            @JsonProperty("column_name") String columnName) {

        static ColumnConfigDto fromResponse(ExecuteSelectTestCaseResponse.ColumnConfig response) {
            return new ColumnConfigDto(response.columnName());
        }
    }
}
