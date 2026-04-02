package graduation_project_be.application.usecases.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record CreateSpecificationRequest(
        String name,
        String ddlScript,
        JsonNode schemaJson,
        String description,
        List<SpecDatasetRequest> datasets,
        List<SpecEntityRequest> entities) {
    public record SpecDatasetRequest(
                        Long id,
            String name,
            String dataScript,
            String tableData,
            int orderIndex,
            Boolean isActive) {
    }

    public record SpecEntityRequest(
            String entityName,
            String displayName,
            String description,
            Integer orderIndex,
            List<SpecAttributeRequest> attributes) {
    }

    public record SpecAttributeRequest(
            String attributeName,
            String dataType,
            String description,
            Boolean isPrimaryKey,
            Boolean isNullable,
            Integer orderIndex) {
    }
}
