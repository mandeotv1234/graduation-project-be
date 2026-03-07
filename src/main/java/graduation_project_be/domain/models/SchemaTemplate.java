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
public class SchemaTemplate {
    private Long id;
    private String name;
    private String ddlScript;
    private String defaultDataScript;
    private Long createdBy;
    private LocalDateTime createdAt;
}
