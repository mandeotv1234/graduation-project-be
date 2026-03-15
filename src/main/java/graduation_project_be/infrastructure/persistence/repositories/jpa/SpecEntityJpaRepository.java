package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.SpecEntityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpecEntityJpaRepository extends JpaRepository<SpecEntityEntity, Long> {

    // Fetch a single entity + its attributes (only 1 bag — no MultipleBagFetchException)
    @Query("SELECT e FROM SpecEntityEntity e LEFT JOIN FETCH e.attributes WHERE e.id = :id")
    Optional<SpecEntityEntity> findByIdWithAttributes(@Param("id") Long id);
}
