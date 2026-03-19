package graduation_project_be.domain.models;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeacherClass {
    private Long classId;
    private Long teacherId;
    private LocalDateTime addedAt;
    
}
