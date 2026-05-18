package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ClassEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface ClassJpaRepository extends JpaRepository<ClassEntity, Long> {
    @Query("""
            SELECT c
            FROM ClassEntity c
            JOIN TeacherClassEntity tc ON tc.classId = c.id
            WHERE tc.teacherId = :teacherId
            """)
    Page<ClassEntity> findAccessibleByTeacherId(@Param("teacherId") Long teacherId, Pageable pageable);

    @Query("""
            SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END
            FROM ClassEntity c
            JOIN TeacherClassEntity tc ON tc.classId = c.id
            WHERE c.id = :classId
              AND tc.teacherId = :teacherId
            """)
    boolean existsTeacherAccess(@Param("classId") Long classId, @Param("teacherId") Long teacherId);

    @Modifying
    @Transactional
    @Query("UPDATE ClassEntity c SET c.deletedAt = :deletedAt WHERE c.id = :classId")
    void updateDeletedAt(@Param("classId") Long classId, @Param("deletedAt") LocalDateTime deletedAt);
}
