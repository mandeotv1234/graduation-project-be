package graduation_project_be.application.usecases.grading.createtable;

import java.util.List;
import java.util.Map;

public record CreateSchemaGraph(Map<String, TableNode> tables) {

    public record TableNode(
            String name,
            Map<String, ColumnNode> columns,
            List<String> columnOrder,
            List<ConstraintNode> constraints) {
    }

    public record ColumnNode(
            String name,
            String rawType,
            String normalizedType,
            Boolean nullable,
            Boolean identity) {
    }

    public record ConstraintNode(
            String type,
            String name,
            List<String> columns,
            String referencedTable,
            List<String> referencedColumns,
            String expression,
            String defaultValue) {

        public boolean isType(String expectedType) {
            return type != null && type.equalsIgnoreCase(expectedType);
        }
    }
}
