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
public class TemplateDataset {
    private Long id;
    private Long templateId;
    private String dataScript;
    private String schemaName;
    private Integer orderIndex;
    private LocalDateTime createdAt;
}
