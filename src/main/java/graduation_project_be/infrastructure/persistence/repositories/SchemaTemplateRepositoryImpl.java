package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.SchemaTemplateRepository;
import graduation_project_be.domain.models.SchemaTemplate;
import graduation_project_be.infrastructure.persistence.entities.SchemaTemplateEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.SchemaTemplateJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SchemaTemplateRepositoryImpl implements SchemaTemplateRepository {

    private final SchemaTemplateJpaRepository jpaRepository;

    @Override
    public Optional<SchemaTemplate> findById(Long id) {
        return jpaRepository.findById(id).map(this::toModel);
    }

    @Override
    public SchemaTemplate save(SchemaTemplate schemaTemplate) {
        SchemaTemplateEntity entity = toEntity(schemaTemplate);
        return toModel(jpaRepository.save(entity));
    }

    @Override
    public List<SchemaTemplate> findAll() {
        return jpaRepository.findAll().stream()
                .map(this::toModel)
                .toList();
    }

    private SchemaTemplate toModel(SchemaTemplateEntity entity) {
        return SchemaTemplate.builder()
                .id(entity.getId())
                .name(entity.getName())
                .ddlScript(entity.getDdlScript())
                .defaultDataScript(entity.getDefaultDataScript())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private SchemaTemplateEntity toEntity(SchemaTemplate model) {
        return SchemaTemplateEntity.builder()
                .id(model.getId())
                .name(model.getName())
                .ddlScript(model.getDdlScript())
                .defaultDataScript(model.getDefaultDataScript())
                .createdBy(model.getCreatedBy())
                .createdAt(model.getCreatedAt())
                .build();
    }
}
