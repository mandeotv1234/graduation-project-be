package graduation_project_be.infrastructure.persistence.entities;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import graduation_project_be.domain.models.ExamSettings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ExamSettingsJson {
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

    public ExamSettings toModel() {
        return ExamSettings.builder()
                .preventCopyPaste(preventCopyPaste)
                .forceFullscreen(forceFullscreen)
                .trackTabSwitch(trackTabSwitch)
                .autoSubmitOnViolation(autoSubmitOnViolation)
                .allowReview(allowReview)
                .scoreDisplayMode(scoreDisplayMode)
                .allowOvertime(allowOvertime)
                .gradingMethod(gradingMethod)
                .maxViolations(maxViolations)
                .showResultAfterSubmit(showResultAfterSubmit)
                .build();
    }

    public static ExamSettingsJson fromModel(ExamSettings model) {
        if (model == null) return null;
        return ExamSettingsJson.builder()
                .preventCopyPaste(model.getPreventCopyPaste())
                .forceFullscreen(model.getForceFullscreen())
                .trackTabSwitch(model.getTrackTabSwitch())
                .autoSubmitOnViolation(model.getAutoSubmitOnViolation())
                .allowReview(model.getAllowReview())
                .scoreDisplayMode(model.getScoreDisplayMode())
                .allowOvertime(model.getAllowOvertime())
                .gradingMethod(model.getGradingMethod())
                .maxViolations(model.getMaxViolations())
                .showResultAfterSubmit(model.getShowResultAfterSubmit())
                .build();
    }
}
