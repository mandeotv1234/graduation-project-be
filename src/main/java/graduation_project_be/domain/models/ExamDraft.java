package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamDraft {
    private Long id;
    private Long examId;
    private Long studentId;
    private List<DraftAnswer> answers;
    private LocalDateTime savedAt;
    private String clientTimestamp;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DraftAnswer {
        private Long questionId;
        private String content;
    }
}
