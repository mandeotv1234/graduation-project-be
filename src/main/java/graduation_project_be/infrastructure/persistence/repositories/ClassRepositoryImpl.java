package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.domain.models.Class;
import graduation_project_be.infrastructure.persistence.entities.ClassEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ClassJpaRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import graduation_project_be.domain.models.enums.SortDirection;

@Repository
@RequiredArgsConstructor
public class ClassRepositoryImpl implements ClassRepository {

    private final ClassJpaRepository classJpaRepository;

    @Override
    public Class save(Class clazz) {
        ClassEntity entity = ClassEntity.fromModel(clazz);
        return classJpaRepository.save(entity).toModel();
    }

    @Override
    public Class findById(Long classId) {
        return classJpaRepository.findById(classId)
                .map(ClassEntity::toModel)
                .orElseThrow(() -> new ResourceNotFoundException("Class", "id", classId));
    }

    @Override
    public PaginatedResult<Class> findByTeacherId(Long teacherId, PaginationParams params) {
        Sort.Direction direction = params.getSortOrder() == SortDirection.DESC
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        String sortBy = params.getSortBy() != null ? params.getSortBy().getFieldName() : "createdAt";

        Pageable pageable = PageRequest.of(params.getPage(), params.getSize(), Sort.by(direction, sortBy));
        Page<ClassEntity> page = classJpaRepository.findByTeacherId(teacherId, pageable);

        return PaginatedResult.of(
                page.getContent().stream().map(ClassEntity::toModel).toList(),
                params.getPage(),
                params.getSize(),
                page.getTotalElements());
    }

    @Override
    public boolean existsByIdAndTeacherId(Long classId, Long teacherId) {
        return classJpaRepository.existsByIdAndTeacherId(classId, teacherId);
    }
}
