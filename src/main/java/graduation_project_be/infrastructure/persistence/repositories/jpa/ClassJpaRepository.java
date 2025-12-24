package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ClassEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface ClassJpaRepository extends JpaRepository<ClassEntity, Long> {
    Page<ClassEntity> findByTeacherId(Long teacherId, Pageable pageable);
}
