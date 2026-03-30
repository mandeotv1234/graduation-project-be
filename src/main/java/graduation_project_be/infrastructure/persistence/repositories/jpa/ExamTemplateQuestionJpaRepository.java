package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamTemplateQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamTemplateQuestionJpaRepository extends JpaRepository<ExamTemplateQuestionEntity, Long> {
    interface TemplateQuestionCountProjection {
        Long getTemplateId();
        long getQuestionCount();
    }

    List<ExamTemplateQuestionEntity> findByTemplateIdOrderByOrderIndexAsc(Long templateId);
    long countByTemplateId(Long templateId);

    @Query("""
            SELECT question.templateId AS templateId, COUNT(question.id) AS questionCount
            FROM ExamTemplateQuestionEntity question
            WHERE question.templateId IN :templateIds
            GROUP BY question.templateId
            """)
    List<TemplateQuestionCountProjection> countByTemplateIds(@Param("templateIds") List<Long> templateIds);
}
