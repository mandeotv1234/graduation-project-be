package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Table(name = "exam_submissions")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamSubmissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "assigned_schema_name", nullable = false)
    private String assignedSchemaName;

    @Column(name = "student_query", columnDefinition = "TEXT")
    private String studentQuery;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    @Column(name = "score_earned")
    private BigDecimal scoreEarned;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "execution_time_ms")
    private Integer executionTimeMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private SubmissionStatus status;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    public ExamSubmission toModel() {
        return ExamSubmission.builder()
                .id(id)
                .examId(examId)
                .questionId(questionId)
                .studentId(studentId)
                .attemptNumber(attemptNumber)
                .assignedSchemaName(assignedSchemaName)
                .studentQuery(studentQuery)
                .isCorrect(isCorrect)
                .scoreEarned(scoreEarned)
                .errorMessage(errorMessage)
                .executionTimeMs(executionTimeMs)
                .status(status)
                .submittedAt(submittedAt)
                .build();
    }

    public static ExamSubmissionEntity fromModel(ExamSubmission model) {
        return ExamSubmissionEntity.builder()
                .id(model.getId())
                .examId(model.getExamId())
                .questionId(model.getQuestionId())
                .studentId(model.getStudentId())
                .attemptNumber(model.getAttemptNumber())
                .assignedSchemaName(model.getAssignedSchemaName())
                .studentQuery(model.getStudentQuery())
                .isCorrect(model.getIsCorrect())
                .scoreEarned(model.getScoreEarned())
                .errorMessage(model.getErrorMessage())
                .executionTimeMs(model.getExecutionTimeMs())
                .status(model.getStatus())
                .submittedAt(model.getSubmittedAt())
                .build();
    }
}
