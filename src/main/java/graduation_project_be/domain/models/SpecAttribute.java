package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecAttribute {
    private Long id;
    private Long entityId;
    private String attributeName;
    private String dataType;
    private String description;
    private boolean isPrimaryKey;
    private boolean isNullable;
    private int orderIndex;
}
