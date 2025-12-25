package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ClassEnrollmentEntity;
import graduation_project_be.infrastructure.persistence.entities.ClassEnrollmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClassEnrollmentJpaRepository extends JpaRepository<ClassEnrollmentEntity, ClassEnrollmentId> {
    boolean existsByClassIdAndStudentId(Long classId, Long studentId);
}
