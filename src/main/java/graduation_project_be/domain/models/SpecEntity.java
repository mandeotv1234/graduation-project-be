package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecEntity {
    private Long id;
    private Long specificationId;
    private String entityName;
    private String displayName;
    private String description;
    private int orderIndex;
    private List<SpecAttribute> attributes;
}
