package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.domain.models.ExamSettings;

public record ExamSettingsResponseDto(
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

    public static ExamSettingsResponseDto fromModel(ExamSettings settings) {
        if (settings == null) return null;
        return new ExamSettingsResponseDto(
                settings.getPreventCopyPaste(),
                settings.getForceFullscreen(),
                settings.getTrackTabSwitch(),
                settings.getAutoSubmitOnViolation(),
                settings.getAllowReview(),
                settings.getScoreDisplayMode(),
                settings.getAllowOvertime(),
                settings.getGradingMethod(),
                settings.getMaxViolations(),
                settings.getShowResultAfterSubmit(),
                settings.getIsLoadDdl(),
                settings.getSeedDatasetId(),
                settings.getHeartbeatIntervalSec(),
                settings.getMaxHeartbeatGapSec(),
                settings.getIntegrityCheckEnabled(),
                settings.getRequireLockdownBrowser());
    }
}
