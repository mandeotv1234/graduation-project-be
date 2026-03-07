package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.SchemaTemplate;
import java.util.List;
import java.util.Optional;

public interface SchemaTemplateRepository {
    Optional<SchemaTemplate> findById(Long id);

    SchemaTemplate save(SchemaTemplate schemaTemplate);

    List<SchemaTemplate> findAll();
}
