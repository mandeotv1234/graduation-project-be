package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamTemplateQuestion;
import graduation_project_be.domain.models.QuestionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "exam_template_questions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamTemplateQuestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "correct_query", nullable = false, columnDefinition = "TEXT")
    private String correctQuery;

    @Column(name = "difficulty_level")
    private Integer difficultyLevel;

    @Column(name = "points", nullable = false)
    private BigDecimal points;

    @Column(name = "order_index")
    private Integer orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false)
    private QuestionType questionType;

    @Column(name = "verify_script", columnDefinition = "TEXT")
    private String verifyScript;

    @Column(name = "grading_rubric", columnDefinition = "TEXT")
    private String gradingRubric;

    public ExamTemplateQuestion toModel() {
        return ExamTemplateQuestion.builder()
                .id(id)
                .templateId(templateId)
                .content(content)
                .correctQuery(correctQuery)
                .difficultyLevel(difficultyLevel)
                .points(points)
                .orderIndex(orderIndex)
                .questionType(questionType)
                .verifyScript(verifyScript)
                .gradingRubric(gradingRubric)
                .build();
    }

    public static ExamTemplateQuestionEntity fromModel(ExamTemplateQuestion model) {
        return ExamTemplateQuestionEntity.builder()
                .id(model.getId())
                .templateId(model.getTemplateId())
                .content(model.getContent())
                .correctQuery(model.getCorrectQuery())
                .difficultyLevel(model.getDifficultyLevel())
                .points(model.getPoints())
                .orderIndex(model.getOrderIndex())
                .questionType(model.getQuestionType())
                .verifyScript(model.getVerifyScript())
                .gradingRubric(model.getGradingRubric())
                .build();
    }
}
