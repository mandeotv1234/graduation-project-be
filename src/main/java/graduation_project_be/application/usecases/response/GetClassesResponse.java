package graduation_project_be.application.usecases.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetClassesResponse {
    private Long id;
    private String classCode;
    private Long teacherId;
    private String semester;
    private LocalDateTime createdAt;
}