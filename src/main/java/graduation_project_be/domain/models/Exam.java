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
public class Exam {
    private Long id;
    private Long specificationId;
    private Long classId;
    private Long creatorId;
    private String title;
    private Integer durationMinutes;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Boolean isPublished;
    private LocalDateTime createdAt;
}
