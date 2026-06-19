package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableMetadata {
    private String tableName;
    @Builder.Default
    private List<ColumnMetadata> columns = new ArrayList<>();
    @Builder.Default
    private List<ForeignKeyMetadata> foreignKeys = new ArrayList<>();
    @Builder.Default
    private List<ConstraintMetadata> constraints = new ArrayList<>();

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForeignKeyMetadata {
        private String constraintName;
        @Builder.Default
        private List<String> columns = new ArrayList<>();
        private String referencesTable;
        @Builder.Default
        private List<String> referencesColumns = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConstraintMetadata {
        private String constraintName;
        private String type;
        @Builder.Default
        private List<String> columns = new ArrayList<>();
        private String referencesTable;
        @Builder.Default
        private List<String> referencesColumns = new ArrayList<>();
        private String expression;
        private String defaultValue;
    }
}
