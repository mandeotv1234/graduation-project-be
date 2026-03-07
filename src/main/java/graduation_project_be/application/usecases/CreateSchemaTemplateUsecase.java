package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.SchemaTemplateRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.CreateSchemaTemplateRequest;
import graduation_project_be.application.usecases.response.SchemaTemplateResponse;
import graduation_project_be.domain.models.SchemaTemplate;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

@RequiredArgsConstructor
public class CreateSchemaTemplateUsecase {

    private final SchemaTemplateRepository schemaTemplateRepository;
    private final CurrentUserService currentUserService;

    public SchemaTemplateResponse execute(CreateSchemaTemplateRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        SchemaTemplate template = SchemaTemplate.builder()
                .name(request.name())
                .ddlScript(request.ddlScript())
                .defaultDataScript(request.defaultDataScript())
                .createdBy(currentUserId)
                .createdAt(LocalDateTime.now())
                .build();

        SchemaTemplate saved = schemaTemplateRepository.save(template);
        return SchemaTemplateResponse.fromModel(saved);
    }
}
