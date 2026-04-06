package graduation_project_be.application.port.services;

import graduation_project_be.domain.models.ExamDeviceConflict;

/**
 * Port for sending real-time notifications related to device conflicts.
 */
public interface DeviceConflictNotificationService {

    /**
     * Notify teachers of the exam's class that a student has triggered a
     * device-conflict and needs approval.
     *
     * @param examId       the exam in conflict
     * @param teacherIds   the list of teachers to notify (global notification)
     * @param conflict     full conflict details for the teacher dialog
     */
    void notifyTeacherConflictPending(Long examId, java.util.List<Long> teacherIds, ExamDeviceConflict conflict);

    /**
     * Notify the student on the NEW device that the teacher has APPROVED the switch.
     * The student's frontend will automatically retry startSession.
     */
    void notifyStudentConflictApproved(Long studentId, Long examId, String conflictId);

    /**
     * Notify the student on the OLD device that their session has been forcibly ended.
     * The student's frontend will show a kicked dialog and redirect to exams list.
     */
    void notifyStudentSessionKicked(Long studentId, Long examId);

    /**
     * Notify the student on the NEW device that the teacher has REJECTED the switch.
     */
    void notifyStudentConflictRejected(Long studentId, Long examId, String reason);
}
