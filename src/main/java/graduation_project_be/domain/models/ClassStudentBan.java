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
public class ClassStudentBan {
    private Long id;
    private Long classId;
    private Long studentId;
    private String reason;
    private Long bannedBy;
    private LocalDateTime bannedAt;
    private boolean active;
}
