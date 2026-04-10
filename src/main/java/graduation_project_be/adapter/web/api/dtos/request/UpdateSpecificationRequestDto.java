package graduation_project_be.adapter.web.api.dtos.request;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.application.usecases.request.CreateSpecificationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record UpdateSpecificationRequestDto(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "DDL script is required") String ddlScript,
        JsonNode schemaJson,
        String description,
        @Valid List<SpecEntityRequestDto> entities,
        @Valid List<SpecDatasetRequestDto> datasets) {

    public record SpecEntityRequestDto(
            @NotBlank(message = "Entity name is required") String entityName,
            String displayName,
            String description,
            Integer orderIndex,
            @Valid List<SpecAttributeRequestDto> attributes) {
    }

    public record SpecAttributeRequestDto(
            @NotBlank(message = "Attribute name is required") String attributeName,
            @NotBlank(message = "Data type is required") String dataType,
            String description,
            Boolean isPrimaryKey,
            Boolean isNullable,
            Integer orderIndex) {
    }

    public record SpecDatasetRequestDto(
            Long id,
            @NotBlank(message = "Dataset name is required") String name,
            String dataScript,
            String tableData,
            Integer orderIndex,
            Boolean isActive) {
    }

    public CreateSpecificationRequest toRequest() {
        List<CreateSpecificationRequest.SpecEntityRequest> entityRequests = entities == null ? List.of()
                : entities.stream().map(entity -> new CreateSpecificationRequest.SpecEntityRequest(
                        entity.entityName(),
                        entity.displayName(),
                        entity.description(),
                        entity.orderIndex(),
                        entity.attributes() == null ? List.of() : entity.attributes().stream()
                                .map(attribute -> new CreateSpecificationRequest.SpecAttributeRequest(
                                        attribute.attributeName(),
                                        attribute.dataType(),
                                        attribute.description(),
                                        attribute.isPrimaryKey(),
                                        attribute.isNullable(),
                                        attribute.orderIndex()))
                                .toList())).toList();

        List<CreateSpecificationRequest.SpecDatasetRequest> datasetRequests = datasets == null ? List.of()
                : datasets.stream().map(dataset -> new CreateSpecificationRequest.SpecDatasetRequest(
                        dataset.id(),
                        dataset.name(),
                        dataset.dataScript() == null ? "" : dataset.dataScript(),
                        dataset.tableData(),
                        dataset.orderIndex() == null ? 0 : dataset.orderIndex(),
                        dataset.isActive())).toList();

        return new CreateSpecificationRequest(
                name,
                ddlScript,
                schemaJson,
                description,
                datasetRequests,
                entityRequests);
    }
}
