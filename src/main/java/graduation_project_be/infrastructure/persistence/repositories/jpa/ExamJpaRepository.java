package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamJpaRepository extends JpaRepository<ExamEntity, Long> {
    Optional<ExamEntity> findByIdAndIsPublished(Long id, Boolean isPublished);

    List<ExamEntity> findByClassId(Long classId);

    @Query("SELECT e FROM ExamEntity e WHERE e.isPublished = true AND e.classId IN " +
            "(SELECT ce.classId FROM ClassEnrollmentEntity ce WHERE ce.studentId = :studentId)")
    List<ExamEntity> findPublishedExamsByStudentId(@Param("studentId") Long studentId);
}
