package graduation_project_be.application.usecases.request;

import java.util.List;

public record SaveExamSpecificationRequest(
        Long examId,
        String name,
        String ddlScript,
        Boolean ddlVisibleToStudent,
        String description,
        List<SpecEntityRequest> entities,
        List<SpecDatasetRequest> datasets) {

    public record SpecEntityRequest(
            String entityName,
            String displayName,
            String description,
            int orderIndex,
            List<SpecAttributeRequest> attributes) {
    }

    public record SpecAttributeRequest(
            String attributeName,
            String dataType,
            String description,
            boolean isPrimaryKey,
            boolean isNullable,
            int orderIndex) {
    }

    public record SpecDatasetRequest(
            Long id,
            String name,
            String dataScript,
            int orderIndex,
            Boolean isActive,
            Boolean visibleToStudent) {
    }
}
