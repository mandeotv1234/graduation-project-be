package graduation_project_be.adapter.web.api.dtos.response;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import graduation_project_be.application.usecases.response.ExamSpecificationResponse;

public record ExamSpecificationResponseDto(
        Long id,
        String name,
        String ddlScript,
        JsonNode schemaJson,
        String schemaDiagram,
        String description,
        List<SpecEntityResponseDto> entities,
        List<SpecDatasetResponseDto> datasets,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public record SpecEntityResponseDto(
            Long id,
            String entityName,
            String displayName,
            String description,
            int orderIndex,
            List<SpecAttributeResponseDto> attributes) {
    }

    public record SpecAttributeResponseDto(
            Long id,
            String attributeName,
            String dataType,
            String description,
            boolean isPrimaryKey,
            boolean isNullable,
            int orderIndex) {
    }

    public record SpecDatasetResponseDto(
            Long id,
            String name,
            String dataScript,
            String tableData,
            int orderIndex,
            boolean isActive) {
    }

    public static ExamSpecificationResponseDto fromResponse(ExamSpecificationResponse r) {
        List<SpecEntityResponseDto> entities = r.entities() == null ? List.of()
                : r.entities().stream().map(e -> {
                    List<SpecAttributeResponseDto> attrs = e.attributes() == null ? List.of()
                            : e.attributes().stream().map(a -> new SpecAttributeResponseDto(
                                    a.id(), a.attributeName(), a.dataType(), a.description(),
                                    a.isPrimaryKey(), a.isNullable(), a.orderIndex())).toList();
                    return new SpecEntityResponseDto(
                            e.id(), e.entityName(), e.displayName(),
                            e.description(), e.orderIndex(), attrs);
                }).toList();

        List<SpecDatasetResponseDto> datasets = r.datasets() == null ? List.of()
                : r.datasets().stream().map(d -> new SpecDatasetResponseDto(
                        d.id(), d.name(), d.dataScript(), d.tableData(), d.orderIndex(), d.isActive())).toList();

        return new ExamSpecificationResponseDto(
                r.id(),
                r.name(),
                r.ddlScript(),
                r.schemaJson(),
                r.schemaDiagram(),
                r.description(),
                entities,
                datasets,
                r.createdBy(),
                r.createdAt(),
                r.updatedAt());
    }
}
