package graduation_project_be.application.usecases.grading.createtable;

import java.util.List;

public record CreateSchemaEdit(
        String target,
        String condition,
        String table,
        List<String> columns,
        String constraintType,
        String referencedTable,
        List<String> referencedColumns,
        String expected,
        String actual,
        String message) {

    public String objectKey(boolean caseSensitive) {
        String tableKey = CreateSchemaNames.normalizeIdentifier(table, caseSensitive);
        String columnKey = CreateSchemaNames.normalizeIdentifierList(columns, caseSensitive);
        String refTableKey = CreateSchemaNames.normalizeIdentifier(referencedTable, caseSensitive);
        String refColumnKey = CreateSchemaNames.normalizeIdentifierList(referencedColumns, caseSensitive);
        return target + "|" + condition + "|" + tableKey + "|" + columnKey + "|" + refTableKey + "|" + refColumnKey;
    }
}
