package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableMetadata {
    private String tableName;
    private List<ColumnMetadata> columns;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnMetadata {
        private String columnName;
        private String dataType;        // Localized display type (e.g. "Chuỗi (5)")
        private String rawDataType;     // Raw SQL type (e.g. "VARCHAR(5)")
        private boolean isPrimaryKey;
        private boolean isUnique;
        private boolean isAutoIncrement;
        private boolean isForeignKey;
        private String referencesTable;
        private String referencesColumn;
        private boolean isNullable;
    }
}
