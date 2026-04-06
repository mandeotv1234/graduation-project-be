package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.DeviceConflictNotificationService;
import graduation_project_be.application.port.services.DeviceConflictStore;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamDeviceConflict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Teacher rejects the device-conflict: new device is denied, existing session continues.
 */
@Slf4j
@RequiredArgsConstructor
public class RejectDeviceConflictUsecase {

    private final DeviceConflictStore conflictStore;
    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final DeviceConflictNotificationService notificationService;

    public void execute(Long examId, String conflictId, String reason) {
        Long teacherId = currentUserService.getCurrentUserId();

        // 1. Verify teacher access
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new BadRequestException("Exam not found"));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("You are not a teacher of this exam's class");
        }

        // 2. Load conflict
        ExamDeviceConflict conflict = conflictStore.findByConflictId(conflictId)
                .orElseThrow(() -> new BadRequestException(
                        "Conflict request not found or has expired."));

        if (!conflict.getExamId().equals(examId)) {
            throw new BadRequestException("Conflict does not belong to this exam");
        }

        Long studentId = conflict.getStudentId();

        // 3. Delete conflict
        conflictStore.delete(conflictId);

        // 4. Notify new device that request was rejected
        String rejectReason = (reason != null && !reason.isBlank())
                ? reason
                : "Giáo viên không cho phép chuyển thiết bị trong khi đang thi.";
        notificationService.notifyStudentConflictRejected(studentId, examId, rejectReason);

        log.info("Device conflict REJECTED by teacher={}: conflictId={}, exam={}, student={}",
                teacherId, conflictId, examId, studentId);
    }
}
