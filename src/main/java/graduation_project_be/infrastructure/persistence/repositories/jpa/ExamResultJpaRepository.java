package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamResultJpaRepository extends JpaRepository<ExamResultEntity, Long> {
    Optional<ExamResultEntity> findFirstByExamIdAndStudentIdOrderByAttemptNumberDesc(Long examId, Long studentId);

    Optional<ExamResultEntity> findByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber);

    @Query("SELECT COUNT(er) FROM ExamResultEntity er WHERE er.examId = :examId AND er.studentId = :studentId")
    Long countByExamIdAndStudentId(@Param("examId") Long examId, @Param("studentId") Long studentId);

    List<ExamResultEntity> findByExamId(Long examId);
}
