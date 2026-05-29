package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ClassStudentBanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassStudentBanJpaRepository extends JpaRepository<ClassStudentBanEntity, Long> {
    Optional<ClassStudentBanEntity> findByClassIdAndStudentIdAndActiveTrue(Long classId, Long studentId);
    List<ClassStudentBanEntity> findByClassIdAndActiveTrueOrderByBannedAtDesc(Long classId);
}
