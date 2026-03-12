package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.ExamSpecificationResponse;

import java.time.LocalDateTime;
import java.util.List;

public record ExamSpecificationResponseDto(
        Long id,
        Long examId,
        String title,
        String description,
        List<SpecEntityResponseDto> entities,
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
        return new ExamSpecificationResponseDto(
                r.id(), r.examId(), r.title(), r.description(),
                entities, r.createdAt(), r.updatedAt());
    }
}
