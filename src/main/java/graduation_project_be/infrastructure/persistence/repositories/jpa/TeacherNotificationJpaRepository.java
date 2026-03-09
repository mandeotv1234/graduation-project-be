package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.TeacherNotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeacherNotificationJpaRepository extends JpaRepository<TeacherNotificationEntity, Long> {

    Page<TeacherNotificationEntity> findByTeacherIdOrderByCreatedAtDesc(Long teacherId, Pageable pageable);

    @Query("SELECT COUNT(n) FROM TeacherNotificationEntity n WHERE n.teacherId = :teacherId AND n.isRead = false")
    long countUnreadByTeacherId(@Param("teacherId") Long teacherId);

    @Modifying
    @Query("UPDATE TeacherNotificationEntity n SET n.isRead = true WHERE n.id = :id AND n.teacherId = :teacherId")
    int markAsRead(@Param("id") Long id, @Param("teacherId") Long teacherId);

    @Modifying
    @Query("UPDATE TeacherNotificationEntity n SET n.isRead = true WHERE n.teacherId = :teacherId AND n.isRead = false")
    int markAllAsRead(@Param("teacherId") Long teacherId);

    @Modifying
    @Query("DELETE FROM TeacherNotificationEntity n WHERE n.id = :id AND n.teacherId = :teacherId")
    int deleteByIdAndTeacherId(@Param("id") Long id, @Param("teacherId") Long teacherId);

    @Modifying
    @Query("DELETE FROM TeacherNotificationEntity n WHERE n.teacherId = :teacherId")
    int deleteAllByTeacherId(@Param("teacherId") Long teacherId);
}
