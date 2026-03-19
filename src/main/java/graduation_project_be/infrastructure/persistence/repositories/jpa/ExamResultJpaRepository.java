package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExamResultJpaRepository extends JpaRepository<ExamResultEntity, Long> {
    Optional<ExamResultEntity> findByExamIdAndStudentId(Long examId, Long studentId);

    long countByExamIdAndStudentId(Long examId, Long studentId);
}
