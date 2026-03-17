package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.SpecEntity;
import graduation_project_be.infrastructure.persistence.entities.ExamSpecificationEntity;
import graduation_project_be.infrastructure.persistence.entities.SpecAttributeEntity;
import graduation_project_be.infrastructure.persistence.entities.SpecDatasetEntity;
import graduation_project_be.infrastructure.persistence.entities.SpecEntityEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamSpecificationJpaRepository;
import graduation_project_be.infrastructure.persistence.repositories.jpa.SpecEntityJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ExamSpecificationRepositoryImpl implements ExamSpecificationRepository {

    private final ExamSpecificationJpaRepository jpaRepository;
    private final SpecEntityJpaRepository specEntityJpaRepository;

    @Override
    @Transactional
    public ExamSpecification save(ExamSpecification specification) {
        ExamSpecificationEntity specEntity = ExamSpecificationEntity.fromModel(specification);

        List<SpecEntityEntity> entityEntities = new ArrayList<>();
        if (specification.getEntities() != null) {
            for (SpecEntity entityModel : specification.getEntities()) {
                SpecEntityEntity entityEntity = SpecEntityEntity.fromModel(entityModel, specEntity);
                List<SpecAttributeEntity> attrEntities = new ArrayList<>();
                if (entityModel.getAttributes() != null) {
                    for (SpecAttribute attrModel : entityModel.getAttributes()) {
                        attrEntities.add(SpecAttributeEntity.fromModel(attrModel, entityEntity));
                    }
                }
                entityEntity.setAttributes(attrEntities);
                entityEntities.add(entityEntity);
            }
        }
        specEntity.setEntities(entityEntities);

        List<SpecDatasetEntity> datasetEntities = new ArrayList<>();
        if (specification.getDatasets() != null) {
            LocalDateTime now = LocalDateTime.now();
            for (SpecDataset datasetModel : specification.getDatasets()) {
                SpecDatasetEntity datasetEntity = SpecDatasetEntity.fromModel(datasetModel, specEntity);
                if (datasetEntity.getCreatedAt() == null) {
                    datasetEntity.setCreatedAt(now);
                }
                datasetEntity.setUpdatedAt(now);
                datasetEntities.add(datasetEntity);
            }
        }
        specEntity.setDatasets(datasetEntities);

        return jpaRepository.save(specEntity).toModel();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExamSpecification> findAll() {
        return jpaRepository.findAll().stream().map(ExamSpecificationEntity::toModel).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExamSpecification> findById(Long id) {
        Optional<ExamSpecificationEntity> specOpt = jpaRepository.findByIdWithEntities(id);
        if (specOpt.isEmpty()) {
            return Optional.empty();
        }

        ExamSpecificationEntity specEntity = specOpt.get();
        jpaRepository.findByIdWithDatasets(id)
                .ifPresent(loaded -> specEntity.setDatasets(loaded.getDatasets()));

        if (specEntity.getEntities() != null) {
            specEntity.getEntities().forEach(entityEntity ->
                    specEntityJpaRepository.findByIdWithAttributes(entityEntity.getId())
                            .ifPresent(loaded -> entityEntity.setAttributes(loaded.getAttributes()))
            );
        }

        return Optional.of(specEntity.toModel());
    }

    @Override
    public boolean existsById(Long id) {
        return jpaRepository.existsById(id);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        jpaRepository.findById(id).ifPresent(jpaRepository::delete);
    }
}
