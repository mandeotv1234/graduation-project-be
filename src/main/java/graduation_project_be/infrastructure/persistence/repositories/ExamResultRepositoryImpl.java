package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.infrastructure.persistence.entities.ExamResultEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamResultJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ExamResultRepositoryImpl implements ExamResultRepository {

    private final ExamResultJpaRepository jpaRepository;

    @Override
    public ExamResult save(ExamResult examResult) {
        ExamResultEntity entity = ExamResultEntity.fromDomain(examResult);
        ExamResultEntity saved = jpaRepository.save(entity);
        return saved.toModel();
    }
}
