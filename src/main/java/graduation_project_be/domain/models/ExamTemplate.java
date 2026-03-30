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
public class ExamTemplate {
    private Long id;
    private Long sourceExamId;
    private Integer version;
    private Long sharedBy;
    private String title;
    private String description;
    private ExamTemplateSpecificationSnapshot specificationSnapshot;
    private LocalDateTime createdAt;
    private Boolean isVisible;
}
