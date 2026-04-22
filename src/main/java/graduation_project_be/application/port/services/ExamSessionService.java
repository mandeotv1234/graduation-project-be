package graduation_project_be.application.port.services;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

public interface ExamSessionService {
    /**
     * Try to start an exam session. Returns true if the session is started
     * successfully.
     * Returns false if a session already exists for this student+exam on a
     * different device.
     */
    boolean tryStartSession(Long examId, Long studentId, String ipAddress, String userAgent);

    /**
     * Check if a session already exists for this student+exam.
     */
    Optional<String> getActiveSession(Long examId, Long studentId);

    /**
     * Completely clear the session and start time for the student+exam.
     * Used when the exam is submitted or the attempt is finished.
     */
    void clearSession(Long examId, Long studentId);

    /**
     * End (release) the session for the student+exam.
     */
    void endSession(Long examId, Long studentId);

    /**
     * Save the backend-controlled exam start time for a student.
     * This is the authoritative time used to calculate remaining seconds.
     */
    void saveExamStartTime(Long examId, Long studentId, LocalDateTime startTime);

    /**
     * Get the backend-controlled exam start time for a student.
     * Returns empty if the student hasn't started the exam yet.
     */
    Optional<LocalDateTime> getExamStartTime(Long examId, Long studentId);

    /**
     * Force-override an existing session with a new device's details.
     * Called by ApproveDeviceConflictUsecase after teacher approves.
     * The old session is replaced; start-time key is preserved.
     */
    void forceOverrideSession(Long examId, Long studentId, String newIpAddress, String newUserAgent);

    /**
     * Get all active student IDs for an exam, checking which students currently have a session.
     */
    Set<Long> getActiveStudentIds(Long examId);

    /**
     * Returns the raw session value (ip|ua) for an existing session, 
     * to extract the existing device info for the conflict dialog.
     */
    Optional<String> getRawSessionValue(Long examId, Long studentId);

    /**
     * Check if the current request's ip address and user agent match the active 
     * session for the student.
     */
    default boolean isSessionValid(Long examId, Long studentId, String ipAddress, String userAgent) {
        return getRawSessionValue(examId, studentId)
                .map(v -> v.equals(ipAddress + "|" + userAgent))
                .orElse(false);
    }
}
