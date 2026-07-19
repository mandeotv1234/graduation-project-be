package graduation_project_be.application.usecases.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.TableMetadata.ColumnMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SpecificationSchemaJsonBuilder {

    private final ObjectMapper objectMapper;

    public JsonNode build(List<TableMetadata> tables) {
        ArrayNode root = objectMapper.createArrayNode();
        if (tables == null) {
            return root;
        }

        for (TableMetadata table : tables) {
            ObjectNode tableNode = root.addObject();
            tableNode.put("tableName", table.getTableName());
            tableNode.put("script", buildCreateTableScript(table));

            ArrayNode columnsNode = tableNode.putArray("columns");
            for (ColumnMetadata column : table.getColumns()) {
                ObjectNode columnNode = columnsNode.addObject();
                columnNode.put("columnName", column.getColumnName());
                columnNode.put("dataType", rawDataType(column));
                columnNode.put("primaryKey", column.isPrimaryKey());
                columnNode.put("foreignKey", column.isForeignKey());
                if (column.getReferencesTable() == null) {
                    columnNode.putNull("referencesTable");
                } else {
                    columnNode.put("referencesTable", column.getReferencesTable());
                }
                if (column.getReferencesColumn() == null) {
                    columnNode.putNull("referencesColumn");
                } else {
                    columnNode.put("referencesColumn", column.getReferencesColumn());
                }
                columnNode.put("nullable", column.isNullable());
                columnNode.put("unique", column.isUnique());
                columnNode.put("autoIncrement", column.isAutoIncrement());
            }

            ArrayNode foreignKeysNode = tableNode.putArray("foreignKeys");
            if (table.getForeignKeys() != null) {
                for (TableMetadata.ForeignKeyMetadata foreignKey : table.getForeignKeys()) {
                    ObjectNode foreignKeyNode = foreignKeysNode.addObject();
                    foreignKeyNode.put("name", foreignKey.getConstraintName());
                    ArrayNode localColumnsNode = foreignKeyNode.putArray("sourceColumns");
                    foreignKey.getColumns().forEach(localColumnsNode::add);
                    foreignKeyNode.put("targetTable", foreignKey.getReferencesTable());
                    ArrayNode referencedColumnsNode = foreignKeyNode.putArray("targetColumns");
                    foreignKey.getReferencesColumns().forEach(referencedColumnsNode::add);
                }
            }
        }

        return root;
    }

    private String buildCreateTableScript(TableMetadata table) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE [").append(table.getTableName()).append("] (\n");

        List<ColumnMetadata> columns = table.getColumns();
        boolean hasPrimaryKey = columns.stream().anyMatch(ColumnMetadata::isPrimaryKey);
        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata column = columns.get(i);
            ddl.append("  [").append(column.getColumnName()).append("] ")
                    .append(rawDataType(column));
            if (column.isAutoIncrement()) {
                ddl.append(" IDENTITY(1,1)");
            }
            if (column.isUnique() && !column.isPrimaryKey()) {
                ddl.append(" UNIQUE");
            }
            if (!column.isNullable() && !column.isPrimaryKey()) {
                ddl.append(" NOT NULL");
            }
            if (i < columns.size() - 1 || hasPrimaryKey) {
                ddl.append(",");
            }
            ddl.append("\n");
        }

        List<String> pkColumns = columns.stream()
                .filter(ColumnMetadata::isPrimaryKey)
                .map(column -> "[" + column.getColumnName() + "]")
                .toList();
        if (!pkColumns.isEmpty()) {
            ddl.append("  PRIMARY KEY (").append(String.join(", ", pkColumns)).append(")\n");
        }
        ddl.append(");");
        return ddl.toString();
    }

    private String rawDataType(ColumnMetadata column) {
        return column.getRawDataType() != null ? column.getRawDataType() : column.getDataType();
    }
}
