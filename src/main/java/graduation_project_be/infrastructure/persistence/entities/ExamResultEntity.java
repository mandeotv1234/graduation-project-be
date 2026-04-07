package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.GradingType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "exam_results")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "total_score", precision = 5, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "max_score", precision = 5, scale = 2)
    private BigDecimal maxScore;

    @Column(name = "total_questions")
    private int totalQuestions;

    @Column(name = "correct_count")
    private int correctCount;

    @Column(name = "late_duration_seconds")
    private int lateDurationSeconds;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private GradingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "grading_type", length = 10, nullable = false)
    @Builder.Default
    private GradingType gradingType = GradingType.AUTO;

    @Column(name = "last_graded_at")
    private LocalDateTime lastGradedAt;

    public ExamResult toModel() {
        return ExamResult.builder()
                .id(id)
                .examId(examId)
                .studentId(studentId)
                .attemptNumber(attemptNumber)
                .totalScore(totalScore)
                .maxScore(maxScore)
                .totalQuestions(totalQuestions)
                .correctCount(correctCount)
                .lateDurationSeconds(lateDurationSeconds)
                .submittedAt(submittedAt)
                .status(status)
                .gradingType(gradingType)
                .lastGradedAt(lastGradedAt)
                .build();
    }

    public static ExamResultEntity fromDomain(ExamResult domain) {
        return ExamResultEntity.builder()
                .id(domain.getId())
                .examId(domain.getExamId())
                .studentId(domain.getStudentId())
                .attemptNumber(domain.getAttemptNumber())
                .totalScore(domain.getTotalScore())
                .maxScore(domain.getMaxScore())
                .totalQuestions(domain.getTotalQuestions())
                .correctCount(domain.getCorrectCount())
                .lateDurationSeconds(domain.getLateDurationSeconds())
                .submittedAt(domain.getSubmittedAt())
                .status(domain.getStatus())
                .gradingType(domain.getGradingType())
                .lastGradedAt(domain.getLastGradedAt())
                .build();
    }
}
