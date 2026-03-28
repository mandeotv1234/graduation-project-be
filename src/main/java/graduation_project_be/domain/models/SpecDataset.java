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
public class SpecDataset {
    private Long id;
    private Long specificationId;
    private String name;
    private String dataScript;
    private String tableData;
    private int orderIndex;
    private boolean isActive;
    private boolean visibleToStudent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
