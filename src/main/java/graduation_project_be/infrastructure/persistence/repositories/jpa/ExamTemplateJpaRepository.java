package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamTemplateJpaRepository extends JpaRepository<ExamTemplateEntity, Long> {
    Optional<ExamTemplateEntity> findByIdAndIsVisibleTrue(Long id);
    List<ExamTemplateEntity> findByIsVisibleTrueOrderBySourceExamIdAscVersionDesc();
    List<ExamTemplateEntity> findBySourceExamIdAndIsVisibleTrueOrderByVersionDesc(Long sourceExamId);
    List<ExamTemplateEntity> findBySourceExamIdOrderByVersionDesc(Long sourceExamId);

    @Query("SELECT COALESCE(MAX(template.version), 0) FROM ExamTemplateEntity template WHERE template.sourceExamId = :sourceExamId")
    Integer findMaxVersionBySourceExamId(@Param("sourceExamId") Long sourceExamId);
}
