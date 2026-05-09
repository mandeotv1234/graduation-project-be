package graduation_project_be.infrastructure.services;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.port.services.NotificationBufferService;
import graduation_project_be.application.port.services.ViolationNotificationService;
import graduation_project_be.domain.models.TeacherNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class WebSocketViolationNotificationService implements ViolationNotificationService {

        private final SimpMessagingTemplate messagingTemplate;
        private final NotificationBufferService notificationBufferService;

        @Override
        public void notifyTeacher(Long examId, List<Long> teacherIds, Long studentId,
                        String studentName, String violationType,
                        String description, int attemptNumber, long violationCount,
                        boolean autoSubmitted) {
                List<Long> targetTeacherIds = teacherIds == null ? List.of() : teacherIds.stream()
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList();

                // 1. Persist notification to DB FIRST so /unread-count API is consistent
                for (Long teacherId : targetTeacherIds) {
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
                                        .createdAt(TimeUtils.now())
                                        .build();

                        try {
                                notificationBufferService.buffer(notification);
                        } catch (Exception e) {
                                log.error("Failed to persist violation notification: exam={}, teacher={}, student={}",
                                                examId, teacherId, studentId, e);
                        }
                }
                log.info("Violation notification persisted for {} teachers: exam={}, student={}",
                                targetTeacherIds.size(), examId, studentId);

                // 2. THEN send real-time notification via WebSocket
                //    DB already has the record, so FE's reconcileUnreadCount() will see it
                String destination = String.format("/topic/exam/%d/violations", examId);

                Map<String, Object> payload = new HashMap<>();
                payload.put("examId", examId);
                payload.put("teacherId", targetTeacherIds.isEmpty() ? 0 : targetTeacherIds.get(0));
                payload.put("teacherIds", targetTeacherIds);
                payload.put("studentId", studentId);
                payload.put("studentName", studentName != null ? studentName : "");
                payload.put("violationType", violationType);
                payload.put("description", description != null ? description : "");
                payload.put("attemptNumber", attemptNumber);
                payload.put("violationCount", violationCount);
                payload.put("autoSubmitted", autoSubmitted);
                payload.put("timestamp", TimeUtils.now().toString());

                messagingTemplate.convertAndSend(destination, payload);
                messagingTemplate.convertAndSend("/topic/teacher/violations", payload);

                log.info("WebSocket violation notification sent: exam={}, student={} ({}), type={}, count={}, autoSubmitted={}",
                                examId, studentId, studentName, violationType, violationCount, autoSubmitted);
        }

        @Override
        public void notifyStudentRemind(Long examId, Long studentId, String action, String message) {
                String destination = String.format("/topic/student/%d/exam-session", studentId);
                Map<String, Object> payload = Map.of(
                                "type", action,
                                "examId", examId,
                                "message", message != null ? message : "Bạn có một thông báo từ giáo viên.",
                                "timestamp", TimeUtils.now().toString());

                messagingTemplate.convertAndSend(destination, payload);
                log.info("WebSocket student reminder sent: exam={}, student={}, action={}, message={}", examId, studentId, action, message);
        }
}
