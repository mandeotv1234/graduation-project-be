package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.TableMetadata.ColumnMetadata;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GenerateSchemaFromDdlUsecase {

    private final ExamSchemaService examSchemaService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public JsonNode execute(String ddlScript) {
        if (ddlScript == null || ddlScript.isBlank()) {
            throw new BadRequestException("ddlScript must be provided");
        }

        Long currentUserId = currentUserService.getCurrentUserId();
        String schemaName = String.format("spec_schema_extract_%d_%d", currentUserId, System.currentTimeMillis());
        try {
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, null);
            List<TableMetadata> metadata = examSchemaService.extractMetadata(schemaName);
            return toSchemaJson(metadata);
        } catch (Exception e) {
            throw new BadRequestException("Invalid DDL script: " + e.getMessage());
        } finally {
            try {
                examSchemaService.dropSchema(schemaName);
            } catch (Exception ignored) {
            }
        }
    }

    private JsonNode toSchemaJson(List<TableMetadata> tables) {
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
                columnNode.put("dataType", column.getRawDataType() != null ? column.getRawDataType() : column.getDataType());
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
        }

        return root;
    }

    private String buildCreateTableScript(TableMetadata table) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE [").append(table.getTableName()).append("] (\n");

        List<ColumnMetadata> columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata column = columns.get(i);
            ddl.append("  [").append(column.getColumnName()).append("] ")
                    .append(column.getRawDataType() != null ? column.getRawDataType() : column.getDataType());
            if (column.isAutoIncrement()) {
                ddl.append(" IDENTITY(1,1)");
            }
            if (column.isUnique() && !column.isPrimaryKey()) {
                ddl.append(" UNIQUE");
            }
            if (!column.isNullable() && !column.isPrimaryKey()) {
                ddl.append(" NOT NULL");
            }
            if (i < columns.size() - 1 || columns.stream().anyMatch(ColumnMetadata::isPrimaryKey)) {
                ddl.append(",");
            }
            ddl.append("\n");
        }

        List<String> pkColumns = columns.stream()
                .filter(ColumnMetadata::isPrimaryKey)
                .map(c -> "[" + c.getColumnName() + "]")
                .toList();
        if (!pkColumns.isEmpty()) {
            ddl.append("  PRIMARY KEY (").append(String.join(", ", pkColumns)).append(")\n");
        }
        ddl.append(");");
        return ddl.toString();
    }
}
