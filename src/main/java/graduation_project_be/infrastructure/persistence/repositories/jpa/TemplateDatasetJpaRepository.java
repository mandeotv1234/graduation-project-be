package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.TemplateDatasetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TemplateDatasetJpaRepository extends JpaRepository<TemplateDatasetEntity, Long> {
    List<TemplateDatasetEntity> findByTemplateId(Long templateId);
}
