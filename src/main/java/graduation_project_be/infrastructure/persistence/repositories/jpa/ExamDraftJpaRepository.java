package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExamDraftJpaRepository extends JpaRepository<ExamDraftEntity, Long> {
    Optional<ExamDraftEntity> findByExamIdAndStudentId(Long examId, Long studentId);

    void deleteByExamIdAndStudentId(Long examId, Long studentId);
}
