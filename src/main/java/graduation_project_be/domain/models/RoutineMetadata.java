package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutineMetadata {
    private String routineName;
    private String routineType; // PROCEDURE or FUNCTION
    private String dataType; // Return type for function

    private List<ParameterMetadata> parameters;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ParameterMetadata {
        private String parameterMode; // IN, OUT, INOUT
        private String parameterName;
        private String dataType;
    }
}
