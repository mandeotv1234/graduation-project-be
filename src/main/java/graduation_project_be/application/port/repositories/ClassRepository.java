package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.Class;

public interface ClassRepository {
    Class save(Class clazz);
}
