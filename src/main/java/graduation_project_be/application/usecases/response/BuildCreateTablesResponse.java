package graduation_project_be.application.usecases.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record BuildCreateTablesResponse(
        List<CreateTableConfig> tables,
        int preparedCount,
        int targetTableCount) {

    public record CreateTableConfig(
            @JsonProperty("expected_name") String expectedName,
            List<CreateColumnConfig> columns,
            List<CreateConstraintConfig> constraints) {
    }

    public record CreateColumnConfig(
            String name,
            @JsonProperty("expected_type") String expectedType,
            @JsonProperty("is_nullable") boolean isNullable,
            @JsonProperty("is_auto_increment") boolean isAutoIncrement) {
    }

    public record CreateConstraintConfig(
            String name,
            String type,
            List<String> columns,
            @JsonProperty("references_table") String referencesTable,
            @JsonProperty("references_columns") List<String> referencesColumns,
            String expression,
            @JsonProperty("default_value") String defaultValue) {
    }
}
