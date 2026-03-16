package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateSpecificationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateSpecificationRequestDto(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "DDL script is required") String ddlScript,
        String description,
        @Valid List<SpecDatasetRequestDto> datasets) {

    public record SpecDatasetRequestDto(
            @NotBlank(message = "Dataset name is required") String name,
            @NotBlank(message = "Dataset script is required") String dataScript,
            int orderIndex,
            Boolean isActive) {
    }

    public CreateSpecificationRequest toRequest() {
        List<CreateSpecificationRequest.SpecDatasetRequest> datasetRequests = datasets == null ? List.of()
                : datasets.stream().map(dataset -> new CreateSpecificationRequest.SpecDatasetRequest(
                        dataset.name(),
                        dataset.dataScript(),
                        dataset.orderIndex(),
                        dataset.isActive())).toList();

        return new CreateSpecificationRequest(name, ddlScript, description, datasetRequests);
    }
}
