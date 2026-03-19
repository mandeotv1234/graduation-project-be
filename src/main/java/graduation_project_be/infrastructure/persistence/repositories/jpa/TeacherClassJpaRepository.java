package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.TeacherClassEntity;
import graduation_project_be.infrastructure.persistence.entities.TeacherClassId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherClassJpaRepository extends JpaRepository<TeacherClassEntity, TeacherClassId> {
    List<TeacherClassEntity> findByClassId(Long classId);
    Optional<TeacherClassEntity> findByClassIdAndTeacherId(Long classId, Long teacherId);
    boolean existsByClassIdAndTeacherId(Long classId, Long teacherId);
    void deleteByClassIdAndTeacherId(Long classId, Long teacherId);
}
