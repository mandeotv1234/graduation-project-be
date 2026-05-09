package graduation_project_be.application.port.services;

import java.util.List;

public interface ViolationNotificationService {

    /**
     * Notify teacher in real-time when a student commits a violation.
     *
     * @param examId         the exam ID
     * @param teacherIds     teachers who should receive the notification
     * @param studentId      the student ID
     * @param studentName    the student's full name
     * @param violationType  type of violation (TAB_SWITCHED, DEVTOOLS_OPENED, etc.)
     * @param description    optional description
     * @param attemptNumber  current exam attempt number
     * @param violationCount total violation count for this student in this exam
     * @param autoSubmitted  whether the exam was auto-submitted due to max violations
     */
    void notifyTeacher(Long examId, List<Long> teacherIds, Long studentId,
                       String studentName, String violationType,
                       String description, int attemptNumber, long violationCount,
                       boolean autoSubmitted);

    void notifyStudentRemind(Long examId, Long studentId, String action, String message);
}
