package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.domain.models.Class;
import graduation_project_be.infrastructure.persistence.entities.ClassEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ClassJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ClassRepositoryImpl implements ClassRepository {

    private final ClassJpaRepository classJpaRepository;

    @Override
    public Class save(Class clazz) {
        ClassEntity entity = ClassEntity.fromModel(clazz);
        return classJpaRepository.save(entity).toModel();
    }
}
