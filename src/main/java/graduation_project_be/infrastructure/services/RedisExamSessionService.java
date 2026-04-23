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
import java.util.Set;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;

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

    private String normalizeIp(String ip) {
        if (ip == null) return "0.0.0.0";
        if (ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) {
            return "127.0.0.1";
        }
        return ip;
    }

    @Override
    public boolean tryStartSession(Long examId, Long studentId, String ipAddress, String userAgent) {
        String key = buildKey(examId, studentId);
        String newValue = buildValue(normalizeIp(ipAddress), userAgent);

        log.info("Try start exam session: exam={}, student={}, ip={}, ua={}", 
                examId, studentId, ipAddress, userAgent);

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
            // Different device — reject (caller will handle conflict notification)
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
        // Double check if that session is for the same device
        String retryExistingValue = redisTemplate.opsForValue().get(key);
        if (newValue.equals(retryExistingValue)) {
            redisTemplate.expire(key, SESSION_TTL);
            log.info("Exam session refreshed (race recovery): exam={}, student={}", examId, studentId);
            return true;
        }

        return false;
    }

    @Override
    public boolean isSessionValid(Long examId, Long studentId, String ipAddress, String userAgent) {
        String expectedValue = buildValue(normalizeIp(ipAddress), userAgent);
        return getRawSessionValue(examId, studentId)
                .map(v -> v.equals(expectedValue))
                .orElse(false);
    }

    @Override
    public Optional<String> getActiveSession(Long examId, Long studentId) {
        String key = buildKey(examId, studentId);
        String value = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(value);
    }

    @Override
    public Optional<String> getRawSessionValue(Long examId, Long studentId) {
        return getActiveSession(examId, studentId);
    }

    @Override
    public void forceOverrideSession(Long examId, Long studentId, String newIpAddress, String newUserAgent) {
        String key = buildKey(examId, studentId);
        String newValue = buildValue(normalizeIp(newIpAddress), newUserAgent);
        redisTemplate.opsForValue().set(key, newValue, SESSION_TTL);
        log.info("Exam session force-overridden: exam={}, student={}, newDevice={}", examId, studentId, newIpAddress);
    }

    @Override
    public Set<Long> getActiveStudentIds(Long examId) {
        String sessionPattern = String.format("exam_session:%d:*", examId);
        String startTimePattern = String.format("exam_start_time:%d:*", examId);
        
        Set<String> sessionKeys = redisTemplate.keys(sessionPattern);
        Set<String> startTimeKeys = redisTemplate.keys(startTimePattern);
        
        Set<Long> activeIds = new java.util.HashSet<>();
        
        if (sessionKeys != null) {
            activeIds.addAll(extractStudentIds(sessionKeys));
        }
        if (startTimeKeys != null) {
            activeIds.addAll(extractStudentIds(startTimeKeys));
        }
        
        return activeIds;
    }

    private Set<Long> extractStudentIds(Set<String> keys) {
        return keys.stream()
            .map(key -> {
                String[] parts = key.split(":");
                if (parts.length >= 3) {
                    try {
                        String lastPart = parts[parts.length - 1];
                        return Long.parseLong(lastPart);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                return null;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
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
