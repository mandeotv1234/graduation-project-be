package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamResultJpaRepository extends JpaRepository<ExamResultEntity, Long> {
    Optional<ExamResultEntity> findByExamIdAndStudentId(Long examId, Long studentId);

    Optional<ExamResultEntity> findByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber);

    long countByExamIdAndStudentId(Long examId, Long studentId);

    List<ExamResultEntity> findByExamId(Long examId);
}
