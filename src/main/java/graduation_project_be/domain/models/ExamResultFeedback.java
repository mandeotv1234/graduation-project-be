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
public class ExamResultFeedback {
    private Long id;
    private Long examResultId;
    private Long examId;
    private Long studentId;
    private int attemptNumber;
    private boolean generatedByAi;
    private LocalDateTime generatedAt;
    private String feedbackJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
