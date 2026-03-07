package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.SchemaTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SchemaTemplateJpaRepository extends JpaRepository<SchemaTemplateEntity, Long> {
}
