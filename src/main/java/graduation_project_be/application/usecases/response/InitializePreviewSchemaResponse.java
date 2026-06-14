package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.TableMetadata;

import java.util.List;

public record InitializePreviewSchemaResponse(List<Table> schema) {

    public static InitializePreviewSchemaResponse fromMetadata(List<TableMetadata> metadata) {
        List<Table> schema = metadata == null
                ? List.of()
                : metadata.stream()
                        .map(Table::fromModel)
                        .toList();
        return new InitializePreviewSchemaResponse(schema);
    }

    public record Table(
            String tableName,
            List<Column> columns) {

        static Table fromModel(TableMetadata metadata) {
            List<Column> columns = metadata.getColumns() == null
                    ? List.of()
                    : metadata.getColumns().stream()
                            .map(Column::fromModel)
                            .toList();
            return new Table(metadata.getTableName(), columns);
        }
    }

    public record Column(
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

        static Column fromModel(TableMetadata.ColumnMetadata metadata) {
            return new Column(
                    metadata.getColumnName(),
                    metadata.getDataType(),
                    metadata.getRawDataType(),
                    metadata.isPrimaryKey(),
                    metadata.isUnique(),
                    metadata.isAutoIncrement(),
                    metadata.isForeignKey(),
                    metadata.getReferencesTable(),
                    metadata.getReferencesColumn(),
                    metadata.isNullable());
        }
    }
}
