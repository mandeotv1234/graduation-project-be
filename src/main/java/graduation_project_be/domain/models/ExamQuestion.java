package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamQuestion {
    private Long id;
    private Long examId;
    private String content;
    private String correctQuery;
    private Integer difficultyLevel;
    private BigDecimal points;
    private Integer orderIndex;
    private QuestionType questionType;
    private String verifyScript;
    private String gradingRubric;
}
