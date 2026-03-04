package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamResult;
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

    @Column(name = "total_score", precision = 5, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "max_score", precision = 5, scale = 2)
    private BigDecimal maxScore;

    @Column(name = "total_questions")
    private int totalQuestions;

    @Column(name = "correct_count")
    private int correctCount;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    public ExamResult toModel() {
        return ExamResult.builder()
                .id(id)
                .examId(examId)
                .studentId(studentId)
                .totalScore(totalScore)
                .maxScore(maxScore)
                .totalQuestions(totalQuestions)
                .correctCount(correctCount)
                .submittedAt(submittedAt)
                .build();
    }

    public static ExamResultEntity fromDomain(ExamResult domain) {
        return ExamResultEntity.builder()
                .id(domain.getId())
                .examId(domain.getExamId())
                .studentId(domain.getStudentId())
                .totalScore(domain.getTotalScore())
                .maxScore(domain.getMaxScore())
                .totalQuestions(domain.getTotalQuestions())
                .correctCount(domain.getCorrectCount())
                .submittedAt(domain.getSubmittedAt())
                .build();
    }
}
