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
public class Class {
    private Long id;
    private String classCode;
    private Long creatorId;
    private String semester;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
}
