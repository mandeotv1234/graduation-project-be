package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.TeacherClass;

import java.util.List;
import java.util.Optional;

public interface TeacherClassRepository {
    TeacherClass save(TeacherClass teacherClass);
    List<TeacherClass> findByClassId(Long classId);
    Optional<TeacherClass> findByClassIdAndTeacherId(Long classId, Long teacherId);
    boolean existsByClassIdAndTeacherId(Long classId, Long teacherId);
    void deleteByClassIdAndTeacherId(Long classId, Long teacherId);
}
