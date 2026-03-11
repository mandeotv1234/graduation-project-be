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
public class TeacherNotification {
    private Long id;
    private Long teacherId;
    private Long examId;
    private Long studentId;
    private String studentName;
    private String violationType;
    private String description;
    private long violationCount;
    private boolean autoSubmitted;
    private boolean isRead;
    private LocalDateTime createdAt;
}
