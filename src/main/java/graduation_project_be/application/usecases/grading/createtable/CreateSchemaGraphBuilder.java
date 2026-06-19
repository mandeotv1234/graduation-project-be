package graduation_project_be.application.usecases.grading.createtable;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.domain.models.TableMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CreateSchemaGraphBuilder {

    private CreateSchemaGraphBuilder() {
    }

    public static CreateSchemaGraph fromRubric(JsonNode tablesNode, boolean caseSensitive) {
        Map<String, CreateSchemaGraph.TableNode> tables = new LinkedHashMap<>();
        if (tablesNode == null || !tablesNode.isArray()) {
            return new CreateSchemaGraph(tables);
        }

        for (JsonNode tableNode : tablesNode) {
            String tableName = tableNode.path("expected_name").asText("");
            if (tableName.isBlank()) {
                continue;
            }

            Map<String, CreateSchemaGraph.ColumnNode> columns = new LinkedHashMap<>();
            List<String> columnOrder = new ArrayList<>();
            for (JsonNode columnNode : tableNode.path("columns")) {
                String columnName = columnNode.path("name").asText("");
                if (columnName.isBlank()) {
                    continue;
                }
                String columnKey = CreateSchemaNames.normalizeIdentifier(columnName, caseSensitive);
                String rawType = columnNode.path("expected_type").asText("");
                columnOrder.add(columnKey);
                columns.put(columnKey, new CreateSchemaGraph.ColumnNode(
                        columnName,
                        rawType,
                        CreateSchemaNames.normalizeSqlType(rawType),
                        columnNode.has("is_nullable") ? columnNode.path("is_nullable").asBoolean() : null,
                        readOptionalBoolean(columnNode, "is_auto_increment", "auto_increment", "identity")));
            }

            List<CreateSchemaGraph.ConstraintNode> constraints = new ArrayList<>();
            for (JsonNode constraintNode : tableNode.path("constraints")) {
                String type = normalizeConstraintType(constraintNode.path("type").asText(""));
                if (type.isBlank()) {
                    continue;
                }
                constraints.add(new CreateSchemaGraph.ConstraintNode(
                        type,
                        constraintNode.path("name").asText(null),
                        readStringList(constraintNode.path("columns")),
                        constraintNode.path("references_table").asText(null),
                        readStringList(constraintNode.path("references_columns")),
                        firstText(constraintNode, "expression", "check_expression"),
                        firstText(constraintNode, "default_value", "defaultValue", "expression")));
            }

            String tableKey = CreateSchemaNames.normalizeIdentifier(tableName, caseSensitive);
            tables.put(tableKey, new CreateSchemaGraph.TableNode(tableName, columns, columnOrder, constraints));
        }

        return new CreateSchemaGraph(tables);
    }

    public static CreateSchemaGraph fromMetadata(List<TableMetadata> metadata, boolean caseSensitive) {
        Map<String, CreateSchemaGraph.TableNode> tables = new LinkedHashMap<>();
        if (metadata == null) {
            return new CreateSchemaGraph(tables);
        }

        for (TableMetadata table : metadata) {
            if (table == null || table.getTableName() == null || table.getTableName().isBlank()) {
                continue;
            }

            Map<String, CreateSchemaGraph.ColumnNode> columns = new LinkedHashMap<>();
            List<String> columnOrder = new ArrayList<>();
            if (table.getColumns() != null) {
                for (TableMetadata.ColumnMetadata column : table.getColumns()) {
                    String columnName = column.getColumnName();
                    if (columnName == null || columnName.isBlank()) {
                        continue;
                    }
                    String columnKey = CreateSchemaNames.normalizeIdentifier(columnName, caseSensitive);
                    String rawType = column.getRawDataType() != null ? column.getRawDataType() : column.getDataType();
                    columnOrder.add(columnKey);
                    columns.put(columnKey, new CreateSchemaGraph.ColumnNode(
                            columnName,
                            rawType,
                            CreateSchemaNames.normalizeSqlType(rawType),
                            column.isNullable(),
                            column.isAutoIncrement()));
                }
            }

            List<CreateSchemaGraph.ConstraintNode> constraints = new ArrayList<>();
            if (table.getConstraints() != null && !table.getConstraints().isEmpty()) {
                for (TableMetadata.ConstraintMetadata constraint : table.getConstraints()) {
                    constraints.add(new CreateSchemaGraph.ConstraintNode(
                            normalizeConstraintType(constraint.getType()),
                            constraint.getConstraintName(),
                            safeList(constraint.getColumns()),
                            constraint.getReferencesTable(),
                            safeList(constraint.getReferencesColumns()),
                            constraint.getExpression(),
                            constraint.getDefaultValue()));
                }
            } else {
                constraints.addAll(legacyConstraints(table));
            }

            String tableKey = CreateSchemaNames.normalizeIdentifier(table.getTableName(), caseSensitive);
            tables.put(tableKey, new CreateSchemaGraph.TableNode(table.getTableName(), columns, columnOrder, constraints));
        }

        return new CreateSchemaGraph(tables);
    }

    private static List<CreateSchemaGraph.ConstraintNode> legacyConstraints(TableMetadata table) {
        List<CreateSchemaGraph.ConstraintNode> constraints = new ArrayList<>();
        if (table.getColumns() != null) {
            List<String> pkColumns = table.getColumns().stream()
                    .filter(TableMetadata.ColumnMetadata::isPrimaryKey)
                    .map(TableMetadata.ColumnMetadata::getColumnName)
                    .toList();
            if (!pkColumns.isEmpty()) {
                constraints.add(new CreateSchemaGraph.ConstraintNode(
                        "PRIMARY_KEY", null, pkColumns, null, List.of(), null, null));
            }

            for (TableMetadata.ColumnMetadata column : table.getColumns()) {
                if (column.isUnique() && !column.isPrimaryKey()) {
                    constraints.add(new CreateSchemaGraph.ConstraintNode(
                            "UNIQUE", null, List.of(column.getColumnName()), null, List.of(), null, null));
                }
            }
        }

        if (table.getForeignKeys() != null) {
            for (TableMetadata.ForeignKeyMetadata foreignKey : table.getForeignKeys()) {
                boolean alreadyExists = constraints.stream().anyMatch(c ->
                        "FOREIGN_KEY".equalsIgnoreCase(c.type()) &&
                                ((c.name() != null && c.name().equalsIgnoreCase(foreignKey.getConstraintName())) ||
                                        (safeList(foreignKey.getColumns()).equals(c.columns()) &&
                                                foreignKey.getReferencesTable().equalsIgnoreCase(c.referencedTable()))));
                if (!alreadyExists) {
                    constraints.add(new CreateSchemaGraph.ConstraintNode(
                            "FOREIGN_KEY",
                            foreignKey.getConstraintName(),
                            safeList(foreignKey.getColumns()),
                            foreignKey.getReferencesTable(),
                            safeList(foreignKey.getReferencesColumns()),
                            null,
                            null));
                }
            }
        }
        return constraints;
    }

    static String normalizeConstraintType(String type) {
        if (type == null) {
            return "";
        }
        return switch (type.trim().toUpperCase()) {
            case "PK", "PRIMARY KEY", "PRIMARY_KEY" -> "PRIMARY_KEY";
            case "FK", "FOREIGN KEY", "FOREIGN_KEY" -> "FOREIGN_KEY";
            case "UQ", "UNIQUE" -> "UNIQUE";
            case "CHECK" -> "CHECK";
            case "DEFAULT" -> "DEFAULT";
            default -> type.trim().toUpperCase();
        };
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private static Boolean readOptionalBoolean(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName)) {
                return node.path(fieldName).asBoolean();
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName) && !node.path(fieldName).asText("").isBlank()) {
                return node.path(fieldName).asText();
            }
        }
        return null;
    }
}
