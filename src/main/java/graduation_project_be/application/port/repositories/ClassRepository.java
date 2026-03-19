package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;

public interface ClassRepository {
    Class save(Class clazz);

    Class findById(Long classId);

    PaginatedResult<Class> findAccessibleByTeacherId(Long teacherId, PaginationParams paginationParams);

    boolean existsTeacherAccess(Long classId, Long teacherId);
}
