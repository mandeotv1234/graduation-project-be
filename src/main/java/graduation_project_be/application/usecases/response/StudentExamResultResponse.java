package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.enums.GradingStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class StudentExamResultResponse {
    private Long id;
    private Long examId;
    private String examTitle;
    private int attemptNumber;
    private BigDecimal totalScore;
    private BigDecimal maxScore;
    private LocalDateTime submittedAt;
    private GradingStatus status;
    private Boolean allowReview;
}
