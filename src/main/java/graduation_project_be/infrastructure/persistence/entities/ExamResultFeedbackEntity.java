package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamResultFeedback;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "exam_result_feedbacks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamResultFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_result_id", nullable = false, unique = true)
    private Long examResultId;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "generated_by_ai", nullable = false)
    private boolean generatedByAi;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "feedback_json", nullable = false, columnDefinition = "TEXT")
    private String feedbackJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ExamResultFeedback toModel() {
        return ExamResultFeedback.builder()
                .id(id)
                .examResultId(examResultId)
                .examId(examId)
                .studentId(studentId)
                .attemptNumber(attemptNumber)
                .generatedByAi(generatedByAi)
                .generatedAt(generatedAt)
                .feedbackJson(feedbackJson)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    public static ExamResultFeedbackEntity fromModel(ExamResultFeedback model) {
        return ExamResultFeedbackEntity.builder()
                .id(model.getId())
                .examResultId(model.getExamResultId())
                .examId(model.getExamId())
                .studentId(model.getStudentId())
                .attemptNumber(model.getAttemptNumber())
                .generatedByAi(model.isGeneratedByAi())
                .generatedAt(model.getGeneratedAt())
                .feedbackJson(model.getFeedbackJson())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();
    }
}
