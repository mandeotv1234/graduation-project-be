package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamViolation {
    private Long id;
    private Long examId;
    private Long studentId;
    private int attemptNumber;
    private String violationType;
    private String description;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdAt;
}
