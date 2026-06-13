package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.RulePreset;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Locale;

@Table(name = "rule_presets")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RulePresetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false)
    private QuestionType questionType;

    @Column(name = "rules_json", nullable = false, columnDefinition = "TEXT")
    private String rulesJson;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = TimeUtils.now();
        }
        if (updatedAt == null) {
            updatedAt = TimeUtils.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = TimeUtils.now();
    }

    public RulePreset toModel() {
        return RulePreset.builder()
                .id(id)
                .teacherId(teacherId)
                .name(name)
                .questionType(questionType)
                .rulesJson(rulesJson)
                .kind(kind)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    public static RulePresetEntity fromModel(RulePreset model) {
        return RulePresetEntity.builder()
                .id(model.getId())
                .teacherId(model.getTeacherId())
                .name(model.getName())
                .questionType(model.getQuestionType())
                .rulesJson(model.getRulesJson())
                .kind(canonicalKind(model.getKind()))
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();
    }

    // Persistence-boundary guard for the non-null kind column: default BLACKBOX on null/blank,
    // canonicalize to upper-case so the discriminator never stores casing/whitespace variants.
    private static String canonicalKind(String kind) {
        return (kind == null || kind.isBlank()) ? "BLACKBOX" : kind.trim().toUpperCase(Locale.ROOT);
    }
}
