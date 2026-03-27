package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.QuestionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Table(name = "exam_questions")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamQuestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

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

    public ExamQuestion toModel() {
        return ExamQuestion.builder()
                .id(id)
                .examId(examId)
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

    public static ExamQuestionEntity fromModel(ExamQuestion model) {
        return ExamQuestionEntity.builder()
                .id(model.getId())
                .examId(model.getExamId())
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
