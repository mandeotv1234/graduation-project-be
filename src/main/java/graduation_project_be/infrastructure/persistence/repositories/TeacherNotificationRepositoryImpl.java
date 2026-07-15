package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.TeacherNotificationRepository;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;
import graduation_project_be.domain.models.TeacherNotification;
import graduation_project_be.infrastructure.persistence.entities.ExamEntity;
import graduation_project_be.infrastructure.persistence.entities.TeacherNotificationEntity;
import graduation_project_be.infrastructure.persistence.entities.UserEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamJpaRepository;
import graduation_project_be.infrastructure.persistence.repositories.jpa.TeacherNotificationJpaRepository;
import graduation_project_be.infrastructure.persistence.repositories.jpa.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
@Slf4j
public class TeacherNotificationRepositoryImpl implements TeacherNotificationRepository {

    private final TeacherNotificationJpaRepository jpaRepository;
    private final ExamJpaRepository examJpaRepository;
    private final UserJpaRepository userJpaRepository;

    @Override
    @Transactional
    public List<TeacherNotification> saveAll(List<TeacherNotification> notifications) {
        List<TeacherNotification> validNotifications = filterValidForeignKeys(notifications);
        if (validNotifications.isEmpty()) {
            return List.of();
        }

        List<TeacherNotificationEntity> entities = validNotifications.stream()
                .map(TeacherNotificationEntity::fromModel)
                .toList();
        return jpaRepository.saveAll(entities).stream()
                .map(TeacherNotificationEntity::toModel)
                .toList();
    }

    @Override
    public PaginatedResult<TeacherNotification> findByTeacherId(Long teacherId, PaginationParams params) {
        Pageable pageable = PageRequest.of(params.getPage(), params.getSize());
        Page<TeacherNotificationEntity> page = jpaRepository.findByTeacherIdOrderByCreatedAtDescIdDesc(teacherId, pageable);

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

    private List<TeacherNotification> filterValidForeignKeys(List<TeacherNotification> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return List.of();
        }

        Set<Long> examIds = notifications.stream()
                .map(TeacherNotification::getExamId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> userIds = new HashSet<>();
        notifications.forEach(notification -> {
            if (notification.getTeacherId() != null) {
                userIds.add(notification.getTeacherId());
            }
            if (notification.getStudentId() != null) {
                userIds.add(notification.getStudentId());
            }
        });

        Set<Long> existingExamIds = examJpaRepository.findAllById(examIds).stream()
                .map(ExamEntity::getId)
                .collect(Collectors.toSet());
        Set<Long> existingUserIds = userJpaRepository.findAllById(userIds).stream()
                .map(UserEntity::getId)
                .collect(Collectors.toSet());

        List<TeacherNotification> validNotifications = notifications.stream()
                .filter(notification -> existingExamIds.contains(notification.getExamId())
                        && existingUserIds.contains(notification.getTeacherId())
                        && existingUserIds.contains(notification.getStudentId()))
                .toList();

        int droppedCount = notifications.size() - validNotifications.size();
        if (droppedCount > 0) {
            log.warn(
                    "Dropped {} stale teacher notifications before persistence: missingExamIds={}, missingUserIds={}",
                    droppedCount,
                    difference(examIds, existingExamIds),
                    difference(userIds, existingUserIds));
        }

        return validNotifications;
    }

    private Set<Long> difference(Set<Long> expectedIds, Set<Long> existingIds) {
        Set<Long> missingIds = new HashSet<>(expectedIds);
        missingIds.removeAll(existingIds);
        return missingIds;
    }
}
