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
        private String dataType;
        private boolean isPrimaryKey;
        private boolean isNullable;
    }
}
