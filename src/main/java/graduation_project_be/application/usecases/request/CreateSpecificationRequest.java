package graduation_project_be.application.usecases.request;

import java.util.List;

public record CreateSpecificationRequest(
        String name,
        String ddlScript,
        String description,
        List<SpecDatasetRequest> datasets) {
    public record SpecDatasetRequest(
            String name,
            String dataScript,
            int orderIndex,
            Boolean isActive) {
    }
}
