package graduation_project_be.application.port.services;

import graduation_project_be.application.usecases.response.SubmitExamResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
                                 List<SubmitExamResponse.QuestionResultItem> questionResults,
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
