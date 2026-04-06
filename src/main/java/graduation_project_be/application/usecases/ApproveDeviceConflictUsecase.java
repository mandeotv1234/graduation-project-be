package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.DeviceConflictNotificationService;
import graduation_project_be.application.port.services.DeviceConflictStore;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamDeviceConflict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Teacher approves the device-conflict: old session is killed, new device is granted access.
 */
@Slf4j
@RequiredArgsConstructor
public class ApproveDeviceConflictUsecase {

    private final DeviceConflictStore conflictStore;
    private final ExamSessionService examSessionService;
    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final DeviceConflictNotificationService notificationService;

    public void execute(Long examId, String conflictId) {
        Long teacherId = currentUserService.getCurrentUserId();

        // 1. Load exam and verify teacher access
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new BadRequestException("Exam not found"));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("You are not a teacher of this exam's class");
        }

        // 2. Load conflict
        ExamDeviceConflict conflict = conflictStore.findByConflictId(conflictId)
                .orElseThrow(() -> new BadRequestException(
                        "Conflict request not found or has expired (>5 minutes). Student should retry."));

        if (!conflict.getExamId().equals(examId)) {
            throw new BadRequestException("Conflict does not belong to this exam");
        }

        Long studentId = conflict.getStudentId();

        // 3. Notify OLD device it's being kicked (before session override)
        notificationService.notifyStudentSessionKicked(studentId, examId);

        // 4. Force-override session to the NEW device
        examSessionService.forceOverrideSession(
                examId, studentId, conflict.getNewIpAddress(), conflict.getNewUserAgent());

        // 5. Delete the conflict record
        conflictStore.delete(conflictId);

        // 6. Notify NEW device that it's approved → FE will auto-retry startSession
        notificationService.notifyStudentConflictApproved(studentId, examId, conflictId);

        log.info("Device conflict APPROVED by teacher={}: conflictId={}, exam={}, student={}",
                teacherId, conflictId, examId, studentId);
    }
}
