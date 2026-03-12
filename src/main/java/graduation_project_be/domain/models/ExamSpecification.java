package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamSpecification {
    private Long id;
    private Long examId;
    private String title;
    private String description;
    private List<SpecEntity> entities;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
