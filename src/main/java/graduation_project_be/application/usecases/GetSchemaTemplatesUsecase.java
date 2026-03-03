package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.SchemaTemplateRepository;
import graduation_project_be.application.usecases.response.SchemaTemplateResponse;
import graduation_project_be.domain.models.SchemaTemplate;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetSchemaTemplatesUsecase {

    private final SchemaTemplateRepository schemaTemplateRepository;

    public List<SchemaTemplateResponse> execute() {
        List<SchemaTemplate> templates = schemaTemplateRepository.findAll();
        return templates.stream()
                .map(SchemaTemplateResponse::fromModel)
                .toList();
    }
}
