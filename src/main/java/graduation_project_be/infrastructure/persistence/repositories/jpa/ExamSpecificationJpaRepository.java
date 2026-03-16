package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamSpecificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ExamSpecificationJpaRepository extends JpaRepository<ExamSpecificationEntity, Long> {

    @Query("SELECT s FROM ExamSpecificationEntity s LEFT JOIN FETCH s.entities WHERE s.id = :id")
    Optional<ExamSpecificationEntity> findByIdWithEntities(@Param("id") Long id);

    @Query("SELECT s FROM ExamSpecificationEntity s LEFT JOIN FETCH s.datasets WHERE s.id = :id")
    Optional<ExamSpecificationEntity> findByIdWithDatasets(@Param("id") Long id);

    @Query("SELECT DISTINCT s FROM ExamSpecificationEntity s LEFT JOIN FETCH s.entities e LEFT JOIN FETCH e.attributes WHERE s.id = :id")
    Optional<ExamSpecificationEntity> findByIdWithDetails(@Param("id") Long id);
}
