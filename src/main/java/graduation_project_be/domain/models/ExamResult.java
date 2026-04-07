package graduation_project_be.domain.models;

import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.GradingType;
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
    private int attemptNumber;
    private BigDecimal totalScore;
    private BigDecimal maxScore;
    private int totalQuestions;
    private int correctCount;
    private int lateDurationSeconds;
    private LocalDateTime submittedAt;
    private GradingStatus status;
    @Builder.Default
    private GradingType gradingType = GradingType.AUTO;
    private LocalDateTime lastGradedAt;
}
