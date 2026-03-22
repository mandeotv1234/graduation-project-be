package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TriggerMetadata {
    private String triggerName;
    private String tableName;
    private boolean isUpdate;
    private boolean isDelete;
    private boolean isInsert;
    private boolean isAfter;
    private boolean isInsteadOf;
    private boolean isDisabled;
}
