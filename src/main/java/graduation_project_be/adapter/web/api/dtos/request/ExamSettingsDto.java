package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.domain.models.ExamSettings;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record ExamSettingsDto(
        Boolean preventCopyPaste,
        Boolean forceFullscreen,
        Boolean trackTabSwitch,
        Boolean autoSubmitOnViolation,
        Boolean allowReview,
        @Pattern(
                regexp = "immediately|after_closed|never",
                message = "Score display mode is invalid")
        String scoreDisplayMode,
        Boolean allowOvertime,
        @Pattern(
                regexp = "highest_score|latest_score|average_score",
                message = "Grading method is invalid")
        String gradingMethod,
        @Min(value = 1, message = "Max violations must be at least 1")
        @Max(value = 100, message = "Max violations must not exceed 100")
        Integer maxViolations,
        Boolean showResultAfterSubmit,
        Boolean isLoadDdl,
        @Positive(message = "Seed dataset ID must be positive") Long seedDatasetId,
        @Min(value = 3, message = "Heartbeat interval must be at least 3 seconds")
        @Max(value = 60, message = "Heartbeat interval must not exceed 60 seconds")
        Integer heartbeatIntervalSec,
        @Min(value = 10, message = "Heartbeat gap must be at least 10 seconds")
        @Max(value = 120, message = "Heartbeat gap must not exceed 120 seconds")
        Integer maxHeartbeatGapSec,
        Boolean integrityCheckEnabled,
        Boolean requireLockdownBrowser) {

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
                .isLoadDdl(isLoadDdl)
                .seedDatasetId(seedDatasetId)
                .heartbeatIntervalSec(heartbeatIntervalSec)
                .maxHeartbeatGapSec(maxHeartbeatGapSec)
                .integrityCheckEnabled(integrityCheckEnabled)
                .requireLockdownBrowser(requireLockdownBrowser)
                .build();
    }
}
