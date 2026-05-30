package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.domain.models.ExamSettings;

public record ExamSettingsDto(
        Boolean preventCopyPaste,
        Boolean forceFullscreen,
        Boolean trackTabSwitch,
        Boolean autoSubmitOnViolation,
        Boolean allowReview,
        String scoreDisplayMode,
        Boolean allowOvertime,
        String gradingMethod,
        Integer maxViolations,
        Boolean showResultAfterSubmit,
        Boolean isLoadDdl,
        Long seedDatasetId,
        Integer heartbeatIntervalSec,
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
