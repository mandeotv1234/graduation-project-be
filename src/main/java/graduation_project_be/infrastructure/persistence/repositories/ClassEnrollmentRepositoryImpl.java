package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.infrastructure.persistence.entities.ClassEnrollmentEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ClassEnrollmentJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ClassEnrollmentRepositoryImpl implements ClassEnrollmentRepository {

    private final ClassEnrollmentJpaRepository classEnrollmentJpaRepository;

    @Override
    public List<ClassEnrollment> saveAll(List<ClassEnrollment> enrollments) {
        List<ClassEnrollmentEntity> entities = enrollments.stream()
                .map(ClassEnrollmentEntity::fromModel)
                .collect(Collectors.toList());
        return classEnrollmentJpaRepository.saveAll(entities).stream()
                .map(ClassEnrollmentEntity::toModel)
                .collect(Collectors.toList());
    }

    @Override
    public List<ClassEnrollment> findByClassId(Long classId) {
        return classEnrollmentJpaRepository.findByClassId(classId).stream()
                .map(ClassEnrollmentEntity::toModel)
                .toList();
    }

    @Override
    public Optional<ClassEnrollment> findByClassIdAndStudentId(Long classId, Long studentId) {
        return classEnrollmentJpaRepository.findByClassIdAndStudentId(classId, studentId)
                .map(ClassEnrollmentEntity::toModel);
    }

    @Override
    public boolean existsByClassIdAndStudentId(Long classId, Long studentId) {
        return classEnrollmentJpaRepository.existsByClassIdAndStudentId(classId, studentId);
    }
}
