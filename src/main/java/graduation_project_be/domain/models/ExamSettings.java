package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamSettings {
    private Boolean preventCopyPaste;
    private Boolean forceFullscreen;
    private Boolean trackTabSwitch;
    private Boolean autoSubmitOnViolation;
    private Boolean allowReview;
    private String scoreDisplayMode;
    private Boolean allowOvertime;
    private String gradingMethod;
    private Integer maxViolations;
    private Boolean showResultAfterSubmit;
}
