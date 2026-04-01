package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.SaveExamSpecificationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SaveExamSpecificationRequestDto(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "DDL script is required") String ddlScript,
        String schemaDiagram,
        String description,
        @Valid List<SpecEntityRequestDto> entities,
        @Valid List<SpecDatasetRequestDto> datasets) {

    public record SpecEntityRequestDto(
            @NotBlank(message = "Entity name is required") String entityName,
            String displayName,
            String description,
            int orderIndex,
            @Valid List<SpecAttributeRequestDto> attributes) {
    }

    public record SpecAttributeRequestDto(
            @NotBlank(message = "Attribute name is required") String attributeName,
            @NotBlank(message = "Data type is required") String dataType,
            String description,
            boolean isPrimaryKey,
            boolean isNullable,
            int orderIndex) {
    }

    public record SpecDatasetRequestDto(
            Long id,
            @NotBlank(message = "Dataset name is required") String name,
            @NotBlank(message = "Dataset script is required") String dataScript,
            String tableData,
            int orderIndex,
            Boolean isActive) {
    }

    public SaveExamSpecificationRequest toRequest(Long examId) {
        List<SaveExamSpecificationRequest.SpecEntityRequest> entityRequests = entities == null ? List.of()
                : entities.stream().map(e -> {
                    List<SaveExamSpecificationRequest.SpecAttributeRequest> attrRequests = e.attributes() == null
                            ? List.of()
                            : e.attributes().stream().map(a -> new SaveExamSpecificationRequest.SpecAttributeRequest(
                                    a.attributeName(), a.dataType(), a.description(),
                                    a.isPrimaryKey(), a.isNullable(), a.orderIndex())).toList();
                    return new SaveExamSpecificationRequest.SpecEntityRequest(
                            e.entityName(), e.displayName(), e.description(),
                            e.orderIndex(), attrRequests);
                }).toList();

        List<SaveExamSpecificationRequest.SpecDatasetRequest> datasetRequests = datasets == null ? List.of()
                : datasets.stream().map(d -> new SaveExamSpecificationRequest.SpecDatasetRequest(
                        d.id(), d.name(), d.dataScript(), d.tableData(), d.orderIndex(), d.isActive())).toList();

        return new SaveExamSpecificationRequest(
                examId,
                name,
                ddlScript,
                schemaDiagram,
                description,
                entityRequests,
                datasetRequests);
    }
}
