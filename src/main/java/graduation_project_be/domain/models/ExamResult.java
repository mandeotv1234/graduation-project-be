package graduation_project_be.domain.models;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamResult {
    private Long id;
    private Long examId;
    private Long studentId;
    private BigDecimal totalScore;
    private BigDecimal maxScore;
    private int totalQuestions;
    private int correctCount;
    private LocalDateTime submittedAt;
}
