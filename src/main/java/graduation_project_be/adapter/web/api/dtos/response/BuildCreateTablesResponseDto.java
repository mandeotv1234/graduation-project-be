package graduation_project_be.adapter.web.api.dtos.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import graduation_project_be.application.usecases.response.BuildCreateTablesResponse;

import java.util.List;

public record BuildCreateTablesResponseDto(
        List<CreateTableConfigDto> tables,
        int preparedCount,
        int targetTableCount) {

    public static BuildCreateTablesResponseDto fromResponse(BuildCreateTablesResponse response) {
        List<CreateTableConfigDto> tables = response.tables().stream()
                .map(CreateTableConfigDto::fromResponse)
                .toList();

        return new BuildCreateTablesResponseDto(tables, response.preparedCount(), response.targetTableCount());
    }

    public record CreateTableConfigDto(
            @JsonProperty("expected_name") String expectedName,
            List<CreateColumnConfigDto> columns,
            List<CreateConstraintConfigDto> constraints) {

        static CreateTableConfigDto fromResponse(BuildCreateTablesResponse.CreateTableConfig response) {
            List<CreateColumnConfigDto> columns = response.columns().stream()
                    .map(CreateColumnConfigDto::fromResponse)
                    .toList();

            List<CreateConstraintConfigDto> constraints = response.constraints().stream()
                    .map(CreateConstraintConfigDto::fromResponse)
                    .toList();

            return new CreateTableConfigDto(response.expectedName(), columns, constraints);
        }
    }

    public record CreateColumnConfigDto(
            String name,
            @JsonProperty("expected_type") String expectedType,
            @JsonProperty("is_nullable") boolean isNullable) {

        static CreateColumnConfigDto fromResponse(BuildCreateTablesResponse.CreateColumnConfig response) {
            return new CreateColumnConfigDto(response.name(), response.expectedType(), response.isNullable());
        }
    }

    public record CreateConstraintConfigDto(
            String type,
            List<String> columns,
            @JsonProperty("references_table") String referencesTable,
            @JsonProperty("references_columns") List<String> referencesColumns) {

        static CreateConstraintConfigDto fromResponse(BuildCreateTablesResponse.CreateConstraintConfig response) {
            return new CreateConstraintConfigDto(
                    response.type(),
                    response.columns(),
                    response.referencesTable(),
                    response.referencesColumns());
        }
    }
}
