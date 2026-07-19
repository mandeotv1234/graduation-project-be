package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.GradingNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
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

    private static final String TEACHER_GLOBAL_GRADING_RESULTS_DESTINATION = "/topic/teacher/grading-results";

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void notifyGradingCompleted(Long examId, Long studentId,
                                        BigDecimal totalScore, BigDecimal maxScore,
                                        int correctCount, int totalQuestions,
                                        String questionResultsJson,
                                        LocalDateTime gradedAt) {
        String destination = String.format("/topic/exam/%d/grading-result", examId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", examId);
        payload.put("studentId", studentId);
        payload.put("totalScore", totalScore);
        payload.put("maxScore", maxScore);
        payload.put("correctCount", correctCount);
        payload.put("totalQuestions", totalQuestions);
        payload.put("status", "COMPLETED");
        payload.put("gradedAt", gradedAt.toString());

        try {
            // Parse the JSON string back into an object structure (List/Map) 
            // so it remains a nested JSON array in the final message.
            payload.put("questionResults", objectMapper.readTree(questionResultsJson));
        } catch (Exception e) {
            log.warn("Failed to parse questionResultsJson in notification service: {}", e.getMessage());
            payload.put("questionResults", questionResultsJson);
        }

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
    public void notifyTeacherGradingCompleted(
            Long examId,
            Long resultId,
            String examName,
            List<Long> teacherIds,
            Long studentId,
            String studentName,
            int attemptNumber,
            BigDecimal totalScore,
            BigDecimal maxScore) {
        String examDestination = String.format("/topic/teacher/exam/%d/grading-result", examId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", examId);
        payload.put("resultId", resultId);
        payload.put("examName", examName != null ? examName : ("Exam " + examId));
        payload.put("teacherIds", teacherIds != null ? teacherIds : List.of());
        payload.put("studentId", studentId);
        payload.put("studentName", studentName != null ? studentName : "Unknown Student");
        payload.put("attemptNumber", attemptNumber);
        payload.put("totalScore", totalScore);
        payload.put("maxScore", maxScore);
        payload.put("status", "COMPLETED");
        payload.put("message", "Sinh viên " + (studentName != null ? studentName : studentId)
                + " đã thi xong bài " + (examName != null ? examName : examId)
                + ". Điểm: " + totalScore + "/" + maxScore);

        messagingTemplate.convertAndSend(examDestination, payload);
        messagingTemplate.convertAndSend(TEACHER_GLOBAL_GRADING_RESULTS_DESTINATION, payload);

        log.info("Teacher notified via WebSocket: student={} completed exam={}, destinations=[{}, {}]",
                studentId, examId, examDestination, TEACHER_GLOBAL_GRADING_RESULTS_DESTINATION);
    }
}
