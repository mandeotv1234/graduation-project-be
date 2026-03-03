package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.SchemaTemplate;
import java.time.LocalDateTime;

public record SchemaTemplateResponse(
        Long id,
        String name,
        String ddlScript,
        String defaultDataScript,
        Long createdBy,
        LocalDateTime createdAt) {
    public static SchemaTemplateResponse fromModel(SchemaTemplate t) {
        return new SchemaTemplateResponse(
                t.getId(), t.getName(), t.getDdlScript(), t.getDefaultDataScript(),
                t.getCreatedBy(), t.getCreatedAt());
    }
}
