package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ClassEnrollmentEntity;
import graduation_project_be.infrastructure.persistence.entities.ClassEnrollmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassEnrollmentJpaRepository extends JpaRepository<ClassEnrollmentEntity, ClassEnrollmentId> {
    List<ClassEnrollmentEntity> findByClassId(Long classId);
    Optional<ClassEnrollmentEntity> findByClassIdAndStudentId(Long classId, Long studentId);
    boolean existsByClassIdAndStudentId(Long classId, Long studentId);
    void deleteByClassId(Long classId);
}

