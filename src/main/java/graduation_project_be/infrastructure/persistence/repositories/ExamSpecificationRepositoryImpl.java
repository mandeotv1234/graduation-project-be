package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecEntity;
import graduation_project_be.infrastructure.persistence.entities.ExamSpecificationEntity;
import graduation_project_be.infrastructure.persistence.entities.SpecAttributeEntity;
import graduation_project_be.infrastructure.persistence.entities.SpecEntityEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamSpecificationJpaRepository;
import graduation_project_be.infrastructure.persistence.repositories.jpa.SpecEntityJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

        return jpaRepository.save(specEntity).toModel();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExamSpecification> findByExamId(Long examId) {
        // Pass 1: fetch spec + entities (single bag)
        Optional<ExamSpecificationEntity> specOpt = jpaRepository.findByExamIdWithEntities(examId);
        if (specOpt.isEmpty()) {
            return Optional.empty();
        }
        ExamSpecificationEntity specEntity = specOpt.get();

        // Pass 2: fetch attributes for each entity (single bag per entity, avoids MultipleBagFetchException)
        if (specEntity.getEntities() != null) {
            specEntity.getEntities().forEach(entityEntity ->
                    specEntityJpaRepository.findByIdWithAttributes(entityEntity.getId())
                            .ifPresent(loaded -> entityEntity.setAttributes(loaded.getAttributes()))
            );
        }

        return Optional.of(specEntity.toModel());
    }

    @Override
    public boolean existsByExamId(Long examId) {
        return jpaRepository.existsByExamId(examId);
    }

    @Override
    @Transactional
    public void deleteByExamId(Long examId) {
        jpaRepository.findByExamId(examId)
                .ifPresent(jpaRepository::delete);
    }
}
