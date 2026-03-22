package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.infrastructure.persistence.entities.ExamResultEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamResultJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    @Override
    public Optional<ExamResult> findByExamIdAndStudentId(Long examId, Long studentId) {
        return jpaRepository.findByExamIdAndStudentId(examId, studentId)
                .map(ExamResultEntity::toModel);
    }

    @Override
    public Optional<ExamResult> findByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber) {
        return jpaRepository.findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber)
                .map(ExamResultEntity::toModel);
    }

    @Override
    public long countByExamIdAndStudentId(Long examId, Long studentId) {
        return jpaRepository.countByExamIdAndStudentId(examId, studentId);
    }

    @Override
    public List<ExamResult> findByExamId(Long examId) {
        return jpaRepository.findByExamId(examId).stream()
                .map(ExamResultEntity::toModel)
                .toList();
    }
}
