package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RulePreset {
    private Long id;
    private Long teacherId;
    private String name;
    private QuestionType questionType;
    private String rulesJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
