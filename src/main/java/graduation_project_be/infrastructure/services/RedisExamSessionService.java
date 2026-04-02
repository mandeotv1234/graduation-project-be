package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.ExamSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class RedisExamSessionService implements ExamSessionService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final Duration SESSION_TTL = Duration.ofHours(4);
    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private String buildKey(Long examId, Long studentId) {
        return String.format("exam_session:%d:%d", examId, studentId);
    }

    private String buildStartTimeKey(Long examId, Long studentId) {
        return String.format("exam_start_time:%d:%d", examId, studentId);
    }

    private String buildValue(String ipAddress, String userAgent) {
        return ipAddress + "|" + userAgent;
    }

    @Override
    public boolean tryStartSession(Long examId, Long studentId, String ipAddress, String userAgent) {
        String key = buildKey(examId, studentId);
        String newValue = buildValue(ipAddress, userAgent);

        // Check if session already exists
        String existingValue = redisTemplate.opsForValue().get(key);

        if (existingValue != null) {
            // Session already exists — check if same device
            if (existingValue.equals(newValue)) {
                // Same device, refresh TTL
                redisTemplate.expire(key, SESSION_TTL);
                log.info("Exam session refreshed: exam={}, student={}", examId, studentId);
                return true;
            }
            // Different device — reject
            log.warn("Exam session conflict: exam={}, student={}, existing={}, new={}",
                    examId, studentId, existingValue, newValue);
            return false;
        }

        // No existing session — create new one
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, newValue, SESSION_TTL);
        if (Boolean.TRUE.equals(success)) {
            log.info("Exam session started: exam={}, student={}", examId, studentId);
            return true;
        }

        // Race condition — another request created the session first
        return false;
    }

    @Override
    public Optional<String> getActiveSession(Long examId, Long studentId) {
        String key = buildKey(examId, studentId);
        String value = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(value);
    }

    @Override
    public void clearSession(Long examId, Long studentId) {
        String sessionKey = buildKey(examId, studentId);
        String startTimeKey = buildStartTimeKey(examId, studentId);
        redisTemplate.delete(List.of(sessionKey, startTimeKey));
        log.info("Exam session and start-time cleared: exam={}, student={}", examId, studentId);
    }

    @Override
    public void endSession(Long examId, Long studentId) {
        String key = buildKey(examId, studentId);
        redisTemplate.delete(key);
        log.info("Exam session ended: exam={}, student={}", examId, studentId);
    }

    @Override
    public void saveExamStartTime(Long examId, Long studentId, LocalDateTime startTime) {
        String key = buildStartTimeKey(examId, studentId);
        String value = startTime.format(DT_FORMATTER);
        redisTemplate.opsForValue().set(key, value, SESSION_TTL);
        log.info("Exam start time saved: exam={}, student={}, startTime={}", examId, studentId, value);
    }

    @Override
    public Optional<LocalDateTime> getExamStartTime(Long examId, Long studentId) {
        String key = buildStartTimeKey(examId, studentId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(LocalDateTime.parse(value, DT_FORMATTER));
    }
}
