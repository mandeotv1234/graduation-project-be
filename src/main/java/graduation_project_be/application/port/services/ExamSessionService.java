package graduation_project_be.application.port.services;

import java.time.LocalDateTime;
import java.util.Optional;

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
}
