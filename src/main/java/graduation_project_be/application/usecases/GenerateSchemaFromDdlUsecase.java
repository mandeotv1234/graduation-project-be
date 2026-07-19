package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.support.SpecificationSchemaJsonBuilder;
import graduation_project_be.domain.models.TableMetadata;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GenerateSchemaFromDdlUsecase {

    private final ExamSchemaService examSchemaService;
    private final CurrentUserService currentUserService;
    private final SpecificationSchemaJsonBuilder schemaJsonBuilder;

    public JsonNode execute(String ddlScript) {
        if (ddlScript == null || ddlScript.isBlank()) {
            throw new BadRequestException("ddlScript must be provided");
        }

        Long currentUserId = currentUserService.getCurrentUserId();
        String schemaName = String.format("spec_schema_extract_%d_%d", currentUserId, System.currentTimeMillis());
        try {
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, null);
            List<TableMetadata> metadata = examSchemaService.extractMetadata(schemaName);
            return schemaJsonBuilder.build(metadata);
        } catch (Exception e) {
            throw new BadRequestException("Invalid DDL script: " + e.getMessage());
        } finally {
            try {
                examSchemaService.dropSchema(schemaName);
            } catch (Exception ignored) {
            }
        }
    }

}
