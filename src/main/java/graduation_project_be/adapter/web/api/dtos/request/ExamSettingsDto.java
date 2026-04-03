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
        Boolean isLoadDdl) {

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
                .build();
    }
}
