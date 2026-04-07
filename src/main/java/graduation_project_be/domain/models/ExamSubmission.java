package graduation_project_be.domain.models;

import graduation_project_be.domain.models.enums.GradingType;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamSubmission {
    private Long id;
    private Long examId;
    private Long questionId;
    private Long studentId;
    private int attemptNumber;
    private String assignedSchemaName;
    private String studentQuery;
    private Boolean isCorrect;
    private BigDecimal scoreEarned;
    private String errorMessage;
    private Integer executionTimeMs;
    private SubmissionStatus status;
    private LocalDateTime submittedAt;
    @Builder.Default
    private GradingType gradingType = GradingType.AUTO;
    private Long gradedBy;
    private LocalDateTime gradedAt;
    private String teacherComment;
}
