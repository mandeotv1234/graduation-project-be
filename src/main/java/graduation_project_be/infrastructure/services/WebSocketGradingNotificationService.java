package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.GradingNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * WebSocket implementation of GradingNotificationService.
 * Sends real-time grading results to the student's frontend via STOMP.
 * 
 * Frontend subscribes to: /topic/exam/{examId}/grading-result
 */
@Slf4j
@RequiredArgsConstructor
public class WebSocketGradingNotificationService implements GradingNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void notifyGradingCompleted(Long examId, Long studentId,
                                        BigDecimal totalScore, BigDecimal maxScore,
                                        int correctCount, int totalQuestions,
                                        LocalDateTime gradedAt) {
        String destination = String.format("/topic/exam/%d/grading-result", examId);

        Map<String, Object> payload = Map.of(
                "examId", examId,
                "studentId", studentId,
                "totalScore", totalScore,
                "maxScore", maxScore,
                "correctCount", correctCount,
                "totalQuestions", totalQuestions,
                "status", "COMPLETED",
                "gradedAt", gradedAt.toString());

        messagingTemplate.convertAndSend(destination, payload);

        log.info("Grading result sent via WebSocket: exam={}, student={}, score={}/{}",
                examId, studentId, totalScore, maxScore);
    }

    @Override
    public void notifyGradingFailed(Long examId, Long studentId, String reason) {
        String destination = String.format("/topic/exam/%d/grading-result", examId);

        Map<String, Object> payload = Map.of(
                "examId", examId,
                "studentId", studentId,
                "status", "FAILED",
                "reason", reason != null ? reason : "Unknown error");

        messagingTemplate.convertAndSend(destination, payload);

        log.warn("Grading failure sent via WebSocket: exam={}, student={}, reason={}",
                examId, studentId, reason);
    }

    @Override
    public void notifyTeacherGradingCompleted(Long examId, String examName, Long studentId,
                                              String studentName, BigDecimal totalScore, BigDecimal maxScore) {
        String destination = String.format("/topic/teacher/exam/%d/grading-result", examId);

        Map<String, Object> payload = Map.of(
                "examId", examId,
                "examName", examName != null ? examName : ("Exam " + examId),
                "studentId", studentId,
                "studentName", studentName != null ? studentName : "Unknown Student",
                "totalScore", totalScore,
                "maxScore", maxScore,
                "status", "COMPLETED",
                "message", "Sinh viên " + (studentName != null ? studentName : studentId) +
                           " đã thi xong bài " + (examName != null ? examName : examId) +
                           ". Điểm: " + totalScore + "/" + maxScore);

        messagingTemplate.convertAndSend(destination, payload);

        log.info("Teacher notified via WebSocket: student={} completed exam={}", studentId, examId);
    }
}
