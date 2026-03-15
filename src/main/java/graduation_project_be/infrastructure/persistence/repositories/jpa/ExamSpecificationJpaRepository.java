package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamSpecificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ExamSpecificationJpaRepository extends JpaRepository<ExamSpecificationEntity, Long> {

    // Fetch spec + entities (1 bag at a time)
    @Query("SELECT s FROM ExamSpecificationEntity s LEFT JOIN FETCH s.entities WHERE s.templateId = :templateId")
    Optional<ExamSpecificationEntity> findByTemplateIdWithEntities(@Param("templateId") Long templateId);

    // Fetch spec + entities + attributes (second pass — Hibernate N+1 avoided via batch)
    @Query("SELECT DISTINCT s FROM ExamSpecificationEntity s LEFT JOIN FETCH s.entities e LEFT JOIN FETCH e.attributes WHERE s.templateId = :templateId")
    Optional<ExamSpecificationEntity> findByTemplateIdWithDetails(@Param("templateId") Long templateId);

    Optional<ExamSpecificationEntity> findByTemplateId(Long templateId);

    boolean existsByTemplateId(Long templateId);
}
