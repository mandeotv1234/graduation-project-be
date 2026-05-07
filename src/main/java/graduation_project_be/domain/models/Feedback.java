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
public class Feedback {
    private Long id;
    private Long studentId;
    private Long examId;
    private Integer uiUxRating;
    private Integer systemReliabilityRating;
    private Integer npsScore;
    private String featureRequests;
    private String generalFeedback;
    private LocalDateTime createdAt;
}
