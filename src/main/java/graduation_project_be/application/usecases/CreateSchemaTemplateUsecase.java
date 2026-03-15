package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.SchemaTemplateRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.CreateSchemaTemplateRequest;
import graduation_project_be.application.usecases.response.SchemaTemplateResponse;
import graduation_project_be.domain.models.SchemaTemplate;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecEntity;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateSchemaTemplateUsecase {

    private final SchemaTemplateRepository schemaTemplateRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final ExamSpecificationRepository examSpecificationRepository;

    @Transactional
    public SchemaTemplateResponse execute(CreateSchemaTemplateRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        SchemaTemplate template = SchemaTemplate.builder()
                .name(request.name())
                .ddlScript(request.ddlScript())
                .defaultDataScript(request.defaultDataScript())
                .createdBy(currentUserId)
                .createdAt(LocalDateTime.now())
                .build();

        SchemaTemplate savedTemplate = schemaTemplateRepository.save(template);

        // Auto-generate specifications if DDL script is provided
        if (request.ddlScript() != null && !request.ddlScript().isBlank()) {
            generateSpecificationForTemplate(savedTemplate);
        }

        return SchemaTemplateResponse.fromModel(savedTemplate);
    }

    private void generateSpecificationForTemplate(SchemaTemplate template) {
        String tempSchemaName = "TEMP_TEMPLATE_" + template.getId() + "_" + System.currentTimeMillis();

        try {
            // 1. Create temporary schema and load DDL
            examSchemaService.loadTemplateIntoSchema(tempSchemaName, template.getDdlScript(), null);

            // 2. Extract metadata
            List<TableMetadata> tables = examSchemaService.extractMetadata(tempSchemaName);

            // 3. Convert metadata to Specification Domain Models
            List<SpecEntity> entities = tables.stream()
                    .map(table -> {
                        List<SpecAttribute> attributes = table.getColumns().stream()
                                .map(col -> SpecAttribute.builder()
                                        .attributeName(col.getColumnName())
                                        .dataType(col.getDataType())
                                        .isNullable(col.isNullable())
                                        .isPrimaryKey(col.isPrimaryKey())
                                        .build())
                                .collect(Collectors.toList());

                        return SpecEntity.builder()
                                .entityName(table.getTableName())
                                .displayName(table.getTableName()) // Default display name to table name
                                .attributes(attributes)
                                .build();
                    })
                    .collect(Collectors.toList());

            ExamSpecification specification = ExamSpecification.builder()
                    .templateId(template.getId())
                    .title("Đặc tả: " + template.getName())
                    .description("Tự động sinh từ DDL script")
                    .entities(entities)
                    .createdAt(LocalDateTime.now())
                    .build();

            // 4. Save to Repository
            examSpecificationRepository.save(specification);
            log.info("Auto-generated specification for template ID: {}", template.getId());

        } catch (Exception e) {
            log.error("Failed to auto-generate specification for template ID: {}. Error: {}", template.getId(), e.getMessage());
            // Depending on requirements, we could re-throw or just log.
            // For now, logging error and proceeding so template creation isn't blocking.
            // throw new RuntimeException("Lỗi sinh đặc tả tự động: " + e.getMessage(), e);
        } finally {
            // 5. Clean up temporary schema
            try {
                examSchemaService.dropSchema(tempSchemaName);
            } catch (Exception e) {
                log.error("Failed to drop temporary schema: {}", tempSchemaName, e);
            }
        }
    }
}
