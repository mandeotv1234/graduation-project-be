package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.domain.models.ClassStudentBan;
import graduation_project_be.infrastructure.persistence.entities.ClassStudentBanEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ClassStudentBanJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ClassStudentBanRepositoryImpl implements ClassStudentBanRepository {

    private final ClassStudentBanJpaRepository jpaRepository;

    @Override
    public ClassStudentBan save(ClassStudentBan ban) {
        ClassStudentBanEntity entity = ClassStudentBanEntity.fromModel(ban);
        return jpaRepository.save(entity).toModel();
    }

    @Override
    public Optional<ClassStudentBan> findActiveByClassIdAndStudentId(Long classId, Long studentId) {
        return jpaRepository.findByClassIdAndStudentIdAndActiveTrue(classId, studentId)
                .map(ClassStudentBanEntity::toModel);
    }

    @Override
    public List<ClassStudentBan> findActiveByClassId(Long classId) {
        return jpaRepository.findByClassIdAndActiveTrueOrderByBannedAtDesc(classId).stream()
                .map(ClassStudentBanEntity::toModel)
                .toList();
    }

    @Override
    public List<Long> findActiveBannedClassIdsByStudentId(Long studentId) {
        return jpaRepository.findByStudentIdAndActiveTrue(studentId).stream()
                .map(ClassStudentBanEntity::getClassId)
                .distinct()
                .toList();
    }
}
