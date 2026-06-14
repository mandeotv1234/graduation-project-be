package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.InitializePreviewSchemaResponse;

import java.util.List;

public record InitializePreviewSchemaResponseDto(List<TableDto> schema) {

    public static InitializePreviewSchemaResponseDto fromResponse(InitializePreviewSchemaResponse response) {
        return new InitializePreviewSchemaResponseDto(
                response.schema().stream()
                        .map(TableDto::fromResponse)
                        .toList());
    }

    public record TableDto(
            String tableName,
            List<ColumnDto> columns) {

        static TableDto fromResponse(InitializePreviewSchemaResponse.Table table) {
            return new TableDto(
                    table.tableName(),
                    table.columns().stream()
                            .map(ColumnDto::fromResponse)
                            .toList());
        }
    }

    public record ColumnDto(
            String columnName,
            String dataType,
            String rawDataType,
            boolean isPrimaryKey,
            boolean isUnique,
            boolean isAutoIncrement,
            boolean isForeignKey,
            String referencesTable,
            String referencesColumn,
            boolean isNullable) {

        static ColumnDto fromResponse(InitializePreviewSchemaResponse.Column column) {
            return new ColumnDto(
                    column.columnName(),
                    column.dataType(),
                    column.rawDataType(),
                    column.isPrimaryKey(),
                    column.isUnique(),
                    column.isAutoIncrement(),
                    column.isForeignKey(),
                    column.referencesTable(),
                    column.referencesColumn(),
                    column.isNullable());
        }
    }
}
