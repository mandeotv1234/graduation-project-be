package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.TeacherNotificationRepository;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;
import graduation_project_be.domain.models.TeacherNotification;
import graduation_project_be.infrastructure.persistence.entities.TeacherNotificationEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.TeacherNotificationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class TeacherNotificationRepositoryImpl implements TeacherNotificationRepository {

    private final TeacherNotificationJpaRepository jpaRepository;

    @Override
    @Transactional
    public List<TeacherNotification> saveAll(List<TeacherNotification> notifications) {
        List<TeacherNotificationEntity> entities = notifications.stream()
                .map(TeacherNotificationEntity::fromModel)
                .toList();
        return jpaRepository.saveAll(entities).stream()
                .map(TeacherNotificationEntity::toModel)
                .toList();
    }

    @Override
    public PaginatedResult<TeacherNotification> findByTeacherId(Long teacherId, PaginationParams params) {
        Pageable pageable = PageRequest.of(params.getPage(), params.getSize());
        Page<TeacherNotificationEntity> page = jpaRepository.findByTeacherIdOrderByCreatedAtDesc(teacherId, pageable);

        List<TeacherNotification> data = page.getContent().stream()
                .map(TeacherNotificationEntity::toModel)
                .toList();

        return PaginatedResult.of(data, params.getPage(), params.getSize(), page.getTotalElements());
    }

    @Override
    public long countUnreadByTeacherId(Long teacherId) {
        return jpaRepository.countUnreadByTeacherId(teacherId);
    }

    @Override
    @Transactional
    public boolean markAsRead(Long id, Long teacherId) {
        return jpaRepository.markAsRead(id, teacherId) > 0;
    }

    @Override
    @Transactional
    public int markAllAsRead(Long teacherId) {
        return jpaRepository.markAllAsRead(teacherId);
    }

    @Override
    @Transactional
    public boolean deleteByIdAndTeacherId(Long id, Long teacherId) {
        return jpaRepository.deleteByIdAndTeacherId(id, teacherId) > 0;
    }

    @Override
    @Transactional
    public int deleteAllByTeacherId(Long teacherId) {
        return jpaRepository.deleteAllByTeacherId(teacherId);
    }
}
