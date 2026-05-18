package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;
import graduation_project_be.domain.models.TeacherClass;

import java.util.List;
import java.util.Optional;

public interface ClassRepository {
    Class save(Class clazz);

    Class findById(Long classId);

    PaginatedResult<Class> findAccessibleByTeacherId(Long teacherId, PaginationParams paginationParams);

    boolean existsTeacherAccess(Long classId, Long teacherId);

    TeacherClass saveTeacherAssociation(TeacherClass teacherClass);

    List<TeacherClass> findTeachersByClassId(Long classId);

    Optional<TeacherClass> findTeacherAssociation(Long classId, Long teacherId);

    void deleteTeacherAssociation(Long classId, Long teacherId);

    void softDelete(Long classId);

    void restore(Long classId);
}
