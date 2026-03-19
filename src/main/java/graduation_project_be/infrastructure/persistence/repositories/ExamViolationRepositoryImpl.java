package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamViolationRepository;
import graduation_project_be.domain.models.ExamViolation;
import graduation_project_be.infrastructure.persistence.entities.ExamViolationEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamViolationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ExamViolationRepositoryImpl implements ExamViolationRepository {

    private final ExamViolationJpaRepository jpaRepository;

    @Override
    public ExamViolation save(ExamViolation violation) {
        ExamViolationEntity entity = ExamViolationEntity.fromModel(violation);
        return jpaRepository.save(entity).toModel();
    }

    @Override
    public List<ExamViolation> findByExamId(Long examId) {
        return jpaRepository.findByExamIdOrderByCreatedAtDesc(examId).stream()
                .map(ExamViolationEntity::toModel)
                .toList();
    }

    @Override
    public List<ExamViolation> findByExamIdAndStudentId(Long examId, Long studentId) {
        return jpaRepository.findByExamIdAndStudentIdOrderByCreatedAtDesc(examId, studentId).stream()
                .map(ExamViolationEntity::toModel)
                .toList();
    }

    @Override
    public long countByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber) {
        return jpaRepository.countByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber);
    }
}
