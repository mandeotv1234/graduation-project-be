package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.SaveExamSpecificationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SaveExamSpecificationRequestDto(
        @NotBlank(message = "Title is required") String title,
        String description,
        @Valid List<SpecEntityRequestDto> entities) {

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
        return new SaveExamSpecificationRequest(examId, title, description, entityRequests);
    }
}
