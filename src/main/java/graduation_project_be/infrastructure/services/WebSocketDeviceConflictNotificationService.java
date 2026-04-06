package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.DeviceConflictNotificationService;
import graduation_project_be.domain.models.ExamDeviceConflict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Sends real-time WebSocket notifications for device conflict events.
 *
 * Teacher channel: /topic/teacher/exam/{examId}/device-conflict
 * Student channel: /topic/student/{studentId}/exam-session
 */
@Slf4j
@RequiredArgsConstructor
public class WebSocketDeviceConflictNotificationService implements DeviceConflictNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void notifyTeacherConflictPending(Long examId, java.util.List<Long> teacherIds, ExamDeviceConflict conflict) {
        // 1. Exam-specific topic (for current monitor page)
        String examDest = String.format("/topic/teacher/exam/%d/device-conflict", examId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "DEVICE_CONFLICT_PENDING");
        payload.put("conflictId", conflict.getConflictId());
        payload.put("examId", conflict.getExamId());
        payload.put("studentId", conflict.getStudentId());
        payload.put("studentName", conflict.getStudentName());
        payload.put("studentEmail", conflict.getStudentEmail());
        payload.put("existingIpAddress", conflict.getExistingIpAddress());
        payload.put("existingUserAgent", conflict.getExistingUserAgent());
        payload.put("newIpAddress", conflict.getNewIpAddress());
        payload.put("newUserAgent", conflict.getNewUserAgent());
        payload.put("requestedAt", conflict.getRequestedAt() != null ? conflict.getRequestedAt().toString() : null);

        messagingTemplate.convertAndSend(examDest, payload);

        // 2. Teacher-specific topics (for any page they are on)
        if (teacherIds != null) {
            for (Long teacherId : teacherIds) {
                String teacherDest = String.format("/topic/teacher/%d/notifications", teacherId);
                messagingTemplate.convertAndSend(teacherDest, payload);
            }
        }

        log.info("Device conflict notification sent to exam={} and {} teachers", examId, teacherIds != null ? teacherIds.size() : 0);
    }

    @Override
    public void notifyStudentConflictApproved(Long studentId, Long examId, String conflictId) {
        String destination = String.format("/topic/student/%d/exam-session", studentId);

        Map<String, Object> payload = Map.of(
                "type", "DEVICE_CONFLICT_APPROVED",
                "examId", examId,
                "conflictId", conflictId,
                "message", "Giáo viên đã cho phép bạn vào thi từ thiết bị này."
        );

        messagingTemplate.convertAndSend(destination, payload);
        log.info("Conflict APPROVED notification sent to student={}, exam={}", studentId, examId);
    }

    @Override
    public void notifyStudentSessionKicked(Long studentId, Long examId) {
        String destination = String.format("/topic/student/%d/exam-session", studentId);

        Map<String, Object> payload = Map.of(
                "type", "SESSION_KICKED",
                "examId", examId,
                "message", "Phiên thi của bạn đã bị chấm dứt do giáo viên cho phép đăng nhập từ thiết bị khác."
        );

        messagingTemplate.convertAndSend(destination, payload);
        log.info("SESSION_KICKED notification sent to student={}, exam={}", studentId, examId);
    }

    @Override
    public void notifyStudentConflictRejected(Long studentId, Long examId, String reason) {
        String destination = String.format("/topic/student/%d/exam-session", studentId);

        Map<String, Object> payload = Map.of(
                "type", "DEVICE_CONFLICT_REJECTED",
                "examId", examId,
                "reason", reason != null ? reason : "Giáo viên không cho phép chuyển thiết bị."
        );

        messagingTemplate.convertAndSend(destination, payload);
        log.info("Conflict REJECTED notification sent to student={}, exam={}", studentId, examId);
    }
}
