package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.Feedback;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "feedbacks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "ui_ux_rating", nullable = false)
    private Integer uiUxRating;

    @Column(name = "system_reliability_rating", nullable = false)
    private Integer systemReliabilityRating;

    @Column(name = "nps_score", nullable = false)
    private Integer npsScore;

    @Column(name = "feature_requests", columnDefinition = "TEXT")
    private String featureRequests;

    @Column(name = "general_feedback", columnDefinition = "TEXT")
    private String generalFeedback;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    public Feedback toModel() {
        return Feedback.builder()
                .id(id)
                .studentId(studentId)
                .examId(examId)
                .uiUxRating(uiUxRating)
                .systemReliabilityRating(systemReliabilityRating)
                .npsScore(npsScore)
                .featureRequests(featureRequests)
                .generalFeedback(generalFeedback)
                .createdAt(createdAt)
                .build();
    }

    public static FeedbackEntity fromModel(Feedback model) {
        return FeedbackEntity.builder()
                .id(model.getId())
                .studentId(model.getStudentId())
                .examId(model.getExamId())
                .uiUxRating(model.getUiUxRating())
                .systemReliabilityRating(model.getSystemReliabilityRating())
                .npsScore(model.getNpsScore())
                .featureRequests(model.getFeatureRequests())
                .generalFeedback(model.getGeneralFeedback())
                .createdAt(model.getCreatedAt())
                .build();
    }
}
