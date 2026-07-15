package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamResultEntity;
import graduation_project_be.domain.models.enums.GradingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ExamResultJpaRepository extends JpaRepository<ExamResultEntity, Long> {
    Optional<ExamResultEntity> findFirstByExamIdAndStudentIdOrderByAttemptNumberDesc(Long examId, Long studentId);

    Optional<ExamResultEntity> findByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber);

    @Query("SELECT COUNT(er) FROM ExamResultEntity er WHERE er.examId = :examId AND er.studentId = :studentId")
    Long countByExamIdAndStudentId(@Param("examId") Long examId, @Param("studentId") Long studentId);

    List<ExamResultEntity> findByExamId(Long examId);

    @Query("""
            SELECT er
            FROM ExamResultEntity er
            WHERE (
                er.status IN :inFlightStatuses
                AND er.submittedAt <= :inFlightSubmittedBefore
            ) OR (
                er.status IN :failedStatuses
                AND COALESCE(er.lastGradedAt, er.submittedAt) <= :failedLastAttemptBefore
            )
            ORDER BY er.submittedAt ASC
            """)
    List<ExamResultEntity> findRecoverableGradingResults(
            @Param("inFlightStatuses") List<GradingStatus> inFlightStatuses,
            @Param("failedStatuses") List<GradingStatus> failedStatuses,
            @Param("inFlightSubmittedBefore") LocalDateTime inFlightSubmittedBefore,
            @Param("failedLastAttemptBefore") LocalDateTime failedLastAttemptBefore,
            Pageable pageable);

    List<ExamResultEntity> findByStudentIdAndExamIdIn(Long studentId, List<Long> examIds);

    Page<ExamResultEntity> findByStudentId(Long studentId, Pageable pageable);
}
