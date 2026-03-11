package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.NotificationBufferService;
import graduation_project_be.application.port.services.ViolationNotificationService;
import graduation_project_be.domain.models.TeacherNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class WebSocketViolationNotificationService implements ViolationNotificationService {

        private final SimpMessagingTemplate messagingTemplate;
        private final NotificationBufferService notificationBufferService;

        @Override
        public void notifyTeacher(Long examId, Long teacherId, Long studentId,
                        String studentName, String violationType,
                        String description, long violationCount,
                        boolean autoSubmitted) {
                // 1. Send real-time notification via WebSocket
                String destination = String.format("/topic/exam/%d/violations", examId);

                Map<String, Object> payload = Map.of(
                                "examId", examId,
                                "teacherId", teacherId,
                                "studentId", studentId,
                                "studentName", studentName != null ? studentName : "",
                                "violationType", violationType,
                                "description", description != null ? description : "",
                                "violationCount", violationCount,
                                "autoSubmitted", autoSubmitted,
                                "timestamp", LocalDateTime.now().toString());

                messagingTemplate.convertAndSend(destination, payload);
                messagingTemplate.convertAndSend("/topic/teacher/violations", payload);

                log.info("WebSocket violation notification sent: exam={}, student={} ({}), type={}, count={}, autoSubmitted={}",
                                examId, studentId, studentName, violationType, violationCount, autoSubmitted);

                // 2. Buffer notification in Redis for persistence
                TeacherNotification notification = TeacherNotification.builder()
                                .teacherId(teacherId)
                                .examId(examId)
                                .studentId(studentId)
                                .studentName(studentName)
                                .violationType(violationType)
                                .description(description)
                                .violationCount(violationCount)
                                .autoSubmitted(autoSubmitted)
                                .isRead(false)
                                .createdAt(LocalDateTime.now())
                                .build();

                notificationBufferService.buffer(notification);
                log.info("Violation notification buffered for persistence: exam={}, student={}", examId, studentId);
        }
}
