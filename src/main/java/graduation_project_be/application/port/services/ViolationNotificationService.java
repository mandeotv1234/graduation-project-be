package graduation_project_be.application.port.services;

public interface ViolationNotificationService {

    /**
     * Notify teacher in real-time when a student commits a violation.
     *
     * @param examId         the exam ID
     * @param teacherId      the teacher (creator) ID
     * @param studentId      the student ID
     * @param studentName    the student's full name
     * @param violationType  type of violation (TAB_SWITCHED, DEVTOOLS_OPENED, etc.)
     * @param description    optional description
     * @param violationCount total violation count for this student in this exam
     * @param autoSubmitted  whether the exam was auto-submitted due to max violations
     */
    void notifyTeacher(Long examId, Long teacherId, Long studentId,
                       String studentName, String violationType,
                       String description, long violationCount,
                       boolean autoSubmitted);
}
