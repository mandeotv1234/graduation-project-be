package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ClassStudentBan;

import java.util.List;
import java.util.Optional;

public interface ClassStudentBanRepository {
    ClassStudentBan save(ClassStudentBan ban);
    Optional<ClassStudentBan> findActiveByClassIdAndStudentId(Long classId, Long studentId);
    List<ClassStudentBan> findActiveByClassId(Long classId);
    List<Long> findActiveBannedClassIdsByStudentId(Long studentId);
}
