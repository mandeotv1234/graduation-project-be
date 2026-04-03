package graduation_project_be.domain.models;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SqlExecutionResult {
    private List<Map<String, Object>> resultSet;
    private int rowCount;
    private String statusMessage;
}
