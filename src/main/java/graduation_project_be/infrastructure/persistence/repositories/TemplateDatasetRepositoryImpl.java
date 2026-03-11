package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.TemplateDatasetRepository;
import graduation_project_be.domain.models.TemplateDataset;
import graduation_project_be.infrastructure.persistence.entities.TemplateDatasetEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.TemplateDatasetJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class TemplateDatasetRepositoryImpl implements TemplateDatasetRepository {

    private final TemplateDatasetJpaRepository jpaRepository;

    @Override
    public TemplateDataset save(TemplateDataset dataset) {
        TemplateDatasetEntity entity = toEntity(dataset);
        return toModel(jpaRepository.save(entity));
    }

    @Override
    public List<TemplateDataset> findByTemplateId(Long templateId) {
        return jpaRepository.findByTemplateId(templateId).stream()
                .map(this::toModel)
                .toList();
    }

    private TemplateDataset toModel(TemplateDatasetEntity entity) {
        return TemplateDataset.builder()
                .id(entity.getId())
                .templateId(entity.getTemplateId())
                .dataScript(entity.getDataScript())
                .schemaName(entity.getSchemaName())
                .orderIndex(entity.getOrderIndex())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private TemplateDatasetEntity toEntity(TemplateDataset model) {
        return TemplateDatasetEntity.builder()
                .id(model.getId())
                .templateId(model.getTemplateId())
                .dataScript(model.getDataScript())
                .schemaName(model.getSchemaName())
                .orderIndex(model.getOrderIndex())
                .createdAt(model.getCreatedAt())
                .build();
    }
}
