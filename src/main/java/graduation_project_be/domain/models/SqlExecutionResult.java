package graduation_project_be.domain.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SqlExecutionResult {
    private List<Map<String, Object>> resultSet;
    private List<String> columns;
    private int rowCount;
    private String statusMessage;

    /**
     * Messages emitted via T-SQL PRINT (and RAISERROR severity 0–10) during the
     * execution. Captured from the JDBC SQLWarning chain on the Statement.
     * Used for grading PRINT_OUTPUT verification type — see T14.
     *
     * <p>Always non-null after execution (empty list when nothing printed) so
     * callers can iterate without a null check. Builder-default keeps lombok
     * builders happy when this field is not explicitly set.
     */
    @Builder.Default
    private List<String> printMessages = new ArrayList<>();
}
