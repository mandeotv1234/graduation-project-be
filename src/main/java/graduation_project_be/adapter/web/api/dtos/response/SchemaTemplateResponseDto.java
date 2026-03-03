package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.SchemaTemplateResponse;
import java.time.LocalDateTime;

public record SchemaTemplateResponseDto(
        Long id,
        String name,
        String ddlScript,
        String defaultDataScript,
        Long createdBy,
        LocalDateTime createdAt) {
    public static SchemaTemplateResponseDto fromResponse(SchemaTemplateResponse r) {
        return new SchemaTemplateResponseDto(
                r.id(), r.name(), r.ddlScript(), r.defaultDataScript(), r.createdBy(), r.createdAt());
    }
}
