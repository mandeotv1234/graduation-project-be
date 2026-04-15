package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.UpdateExamRequest;
import graduation_project_be.application.usecases.response.UpdateExamResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class UpdateExamUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public UpdateExamResponse execute(UpdateExamRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Long examId = request.examId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // Check if user is teacher of the class
        boolean isTeacherOfClass = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        if (!isTeacherOfClass) {
            throw new UnauthorizedException("User is not authorized to update this exam");
        }

        // Update fields if provided
        if (request.title() != null) {
            exam.setTitle(request.title());
        }
        if (request.specificationId() != null) {
            Long sid = request.specificationId();
            if (sid != null && sid > 0L) {
                exam.setSpecificationId(sid);
                exam.setPdfFilePath(null);
                exam.setOriginalPdfFileName(null);
            } else {
                exam.setSpecificationId(null);
            }
        }
        if (request.durationMinutes() != null) {
            exam.setDurationMinutes(request.durationMinutes());
        }
        if (request.startTime() != null) {
            exam.setStartTime(request.startTime());
        }
        if (request.endTime() != null) {
            exam.setEndTime(request.endTime());
        }
        if (request.isPublished() != null) {
            exam.setIsPublished(request.isPublished());
        }
        if (request.description() != null) {
            exam.setDescription(request.description());
        }
        if (request.maxAttempts() != null) {
            exam.setMaxAttempts(request.maxAttempts());
        }
        if (request.lateThreshold() != null) {
            exam.setLateThreshold(request.lateThreshold());
        }
        if (request.settings() != null) {
            exam.setSettings(mergeExamSettings(exam.getSettings(), request.settings()));
        }

        if (request.pdfFilePath() != null) {
            exam.setPdfFilePath(request.pdfFilePath());
            exam.setOriginalPdfFileName(request.originalPdfFileName());
            exam.setSpecificationId(null);
        }

        Exam savedExam = examRepository.save(exam);
        log.info("Exam updated successfully: {}", savedExam.getId());

        return UpdateExamResponse.fromModel(savedExam);
    }

    /**
     * PATCH-style: chỉ ghi đè field có trong payload; null trong payload giữ giá trị hiện tại.
     */
    private ExamSettings mergeExamSettings(ExamSettings current, ExamSettings patch) {
        if (current == null) {
            return patch;
        }
        if (patch == null) {
            return current;
        }
        return ExamSettings.builder()
                .preventCopyPaste(
                        patch.getPreventCopyPaste() != null
                                ? patch.getPreventCopyPaste()
                                : current.getPreventCopyPaste())
                .forceFullscreen(
                        patch.getForceFullscreen() != null
                                ? patch.getForceFullscreen()
                                : current.getForceFullscreen())
                .trackTabSwitch(
                        patch.getTrackTabSwitch() != null
                                ? patch.getTrackTabSwitch()
                                : current.getTrackTabSwitch())
                .autoSubmitOnViolation(
                        patch.getAutoSubmitOnViolation() != null
                                ? patch.getAutoSubmitOnViolation()
                                : current.getAutoSubmitOnViolation())
                .allowReview(
                        patch.getAllowReview() != null
                                ? patch.getAllowReview()
                                : current.getAllowReview())
                .scoreDisplayMode(
                        patch.getScoreDisplayMode() != null
                                ? patch.getScoreDisplayMode()
                                : current.getScoreDisplayMode())
                .allowOvertime(
                        patch.getAllowOvertime() != null
                                ? patch.getAllowOvertime()
                                : current.getAllowOvertime())
                .gradingMethod(
                        patch.getGradingMethod() != null
                                ? patch.getGradingMethod()
                                : current.getGradingMethod())
                .maxViolations(
                        patch.getMaxViolations() != null
                                ? patch.getMaxViolations()
                                : current.getMaxViolations())
                .showResultAfterSubmit(
                        patch.getShowResultAfterSubmit() != null
                                ? patch.getShowResultAfterSubmit()
                                : current.getShowResultAfterSubmit())
                .build();
    }
}
