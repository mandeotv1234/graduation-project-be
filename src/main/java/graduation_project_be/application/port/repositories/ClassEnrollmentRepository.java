package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ClassEnrollment;
import java.util.List;
import java.util.Optional;

public interface ClassEnrollmentRepository {
    List<ClassEnrollment> saveAll(List<ClassEnrollment> enrollments);
    List<ClassEnrollment> findByClassId(Long classId);
    Optional<ClassEnrollment> findByClassIdAndStudentId(Long classId, Long studentId);
    boolean existsByClassIdAndStudentId(Long classId, Long studentId);
    void deleteByClassId(Long classId);
}

