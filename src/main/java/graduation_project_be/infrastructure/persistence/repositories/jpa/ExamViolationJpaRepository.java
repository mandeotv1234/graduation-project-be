package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamViolationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamViolationJpaRepository extends JpaRepository<ExamViolationEntity, Long> {
    List<ExamViolationEntity> findByExamIdOrderByCreatedAtDesc(Long examId);
    List<ExamViolationEntity> findByExamIdAndStudentIdOrderByCreatedAtDesc(Long examId, Long studentId);
    long countByExamIdAndStudentId(Long examId, Long studentId);
}
