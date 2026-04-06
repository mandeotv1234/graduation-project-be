package graduation_project_be.infrastructure.persistence.entities;

import jakarta.persistence.*;
import lombok.*;
import graduation_project_be.domain.models.ExamDraft;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "exam_drafts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"exam_id", "student_id"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamDraftEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "answers_json", nullable = false, columnDefinition = "TEXT")
    private String answersJson;

    @Column(name = "saved_at", nullable = false)
    private LocalDateTime savedAt;

    @Column(name = "client_timestamp", length = 50)
    private String clientTimestamp;

    public ExamDraft toModel(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        try {
            var type = objectMapper.getTypeFactory()
                    .constructCollectionType(java.util.List.class, ExamDraft.DraftAnswer.class);
            java.util.List<ExamDraft.DraftAnswer> answers = objectMapper.readValue(answersJson, type);
            return ExamDraft.builder()
                    .id(id)
                    .examId(examId)
                    .studentId(studentId)
                    .answers(answers)
                    .savedAt(savedAt)
                    .clientTimestamp(clientTimestamp)
                    .build();
        } catch (Exception e) {
            return ExamDraft.builder()
                    .id(id)
                    .examId(examId)
                    .studentId(studentId)
                    .answers(java.util.List.of())
                    .savedAt(savedAt)
                    .clientTimestamp(clientTimestamp)
                    .build();
        }
    }

    public static ExamDraftEntity fromModel(ExamDraft model, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        try {
            String json = objectMapper.writeValueAsString(model.getAnswers());
            return ExamDraftEntity.builder()
                    .id(model.getId())
                    .examId(model.getExamId())
                    .studentId(model.getStudentId())
                    .answersJson(json)
                    .savedAt(model.getSavedAt())
                    .clientTimestamp(model.getClientTimestamp())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize draft answers", e);
        }
    }
}
