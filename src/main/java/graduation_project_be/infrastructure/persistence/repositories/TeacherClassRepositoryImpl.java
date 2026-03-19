package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.TeacherClassRepository;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.infrastructure.persistence.entities.TeacherClassEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.TeacherClassJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TeacherClassRepositoryImpl implements TeacherClassRepository {

    private final TeacherClassJpaRepository teacherClassJpaRepository;

    @Override
    public TeacherClass save(TeacherClass teacherClass) {
        TeacherClassEntity entity = TeacherClassEntity.fromModel(teacherClass);
        return teacherClassJpaRepository.save(entity).toModel();
    }

    @Override
    public List<TeacherClass> findByClassId(Long classId) {
        return teacherClassJpaRepository.findByClassId(classId).stream()
                .map(TeacherClassEntity::toModel)
                .toList();
    }

    @Override
    public Optional<TeacherClass> findByClassIdAndTeacherId(Long classId, Long teacherId) {
        return teacherClassJpaRepository.findByClassIdAndTeacherId(classId, teacherId)
                .map(TeacherClassEntity::toModel);
    }

    @Override
    public boolean existsByClassIdAndTeacherId(Long classId, Long teacherId) {
        return teacherClassJpaRepository.existsByClassIdAndTeacherId(classId, teacherId);
    }

    @Override
    public void deleteByClassIdAndTeacherId(Long classId, Long teacherId) {
        teacherClassJpaRepository.deleteByClassIdAndTeacherId(classId, teacherId);
    }
}
