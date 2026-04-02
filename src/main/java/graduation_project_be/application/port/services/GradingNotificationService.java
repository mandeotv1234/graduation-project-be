package graduation_project_be.application.port.services;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Port interface to notify students about grading completion.
 * The infrastructure layer implements this via WebSocket.
 */
public interface GradingNotificationService {

    /**
     * Notify a student that their exam has been graded.
     */
    void notifyGradingCompleted(Long examId, Long studentId,
                                 BigDecimal totalScore, BigDecimal maxScore,
                                 int correctCount, int totalQuestions,
                                 String questionResultsJson,
                                 LocalDateTime gradedAt);

    /**
     * Notify a student that grading has failed.
     *
     * @param examId    the exam ID
     * @param studentId the student ID
     * @param reason    failure reason
     */
    void notifyGradingFailed(Long examId, Long studentId, String reason);

    /**
     * Notify teachers that a student's grading is complete.
     */
    void notifyTeacherGradingCompleted(Long examId, String examName, Long studentId,
                                       String studentName, BigDecimal totalScore, BigDecimal maxScore);
}
