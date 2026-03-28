package graduation_project_be.adapter.web.api.dtos.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import graduation_project_be.application.usecases.request.CreateSpecificationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateSpecificationV2RequestDto(
        @JsonAlias({"title", "name"}) @NotBlank(message = "Title is required") String title,
        String ddlScript,
        Boolean ddlVisibleToStudent,
        String description,
        @Valid List<SpecEntityRequestDto> entities,
        @Valid List<SpecDatasetRequestDto> datasets) {

    public record SpecEntityRequestDto(
            String entityName,
            String displayName,
            String description,
            Integer orderIndex,
            @Valid List<SpecAttributeRequestDto> attributes) {
    }

    public record SpecAttributeRequestDto(
            String attributeName,
            String dataType,
            String description,
            Boolean isPrimaryKey,
            Boolean isNullable,
            Integer orderIndex) {
    }

    public record SpecDatasetRequestDto(
            Long id,
            @NotBlank(message = "Dataset name is required") String name,
            @NotBlank(message = "Dataset script is required") String dataScript,
            int orderIndex,
            Boolean isActive,
            Boolean visibleToStudent) {
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
                        dataset.dataScript(),
                        dataset.orderIndex(),
                        dataset.isActive(),
                        dataset.visibleToStudent())).toList();

        return new CreateSpecificationRequest(title, ddlScript, ddlVisibleToStudent, description, datasetRequests, entityRequests);
    }
}
