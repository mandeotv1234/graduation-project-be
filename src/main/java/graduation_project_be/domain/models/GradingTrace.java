package graduation_project_be.domain.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GradingTrace(
    int traceSchemaVersion,
    String gradingRunVersion,
    LocalDateTime generatedAt,
    int attemptNumber,
    String rubricHash,
    List<GradingTraceItem> items
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String CURRENT_RUN_VERSION = "grade-exam-v1";
}
