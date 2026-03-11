package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.repositories.TeacherNotificationRepository;
import graduation_project_be.application.port.services.NotificationBufferService;
import graduation_project_be.domain.models.TeacherNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class RedisNotificationBufferService implements NotificationBufferService {

    private final RedisTemplate<String, String> redisTemplate;
    private final TeacherNotificationRepository teacherNotificationRepository;
    private final int flushThreshold;

    private static final String BUFFER_KEY = "notification_buffer";
    private static final String SEPARATOR = "||";
    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public RedisNotificationBufferService(RedisTemplate<String, String> redisTemplate,
            TeacherNotificationRepository teacherNotificationRepository,
            int flushThreshold) {
        this.redisTemplate = redisTemplate;
        this.teacherNotificationRepository = teacherNotificationRepository;
        this.flushThreshold = flushThreshold;
    }

    @Override
    public void buffer(TeacherNotification notification) {
        String serialized = serialize(notification);
        redisTemplate.opsForList().rightPush(BUFFER_KEY, serialized);

        Long currentSize = redisTemplate.opsForList().size(BUFFER_KEY);
        log.info("Notification buffered in Redis. Buffer size: {}/{}", currentSize, flushThreshold);

        if (currentSize != null && currentSize >= flushThreshold) {
            log.info("Buffer threshold reached ({}). Flushing to database...", flushThreshold);
            flush();
        }
    }

    @Override
    public synchronized List<TeacherNotification> flush() {
        Long size = redisTemplate.opsForList().size(BUFFER_KEY);
        if (size == null || size == 0) {
            log.debug("Notification buffer is empty. Nothing to flush.");
            return List.of();
        }

        List<TeacherNotification> notifications = new ArrayList<>();
        for (long i = 0; i < size; i++) {
            String serialized = redisTemplate.opsForList().leftPop(BUFFER_KEY);
            if (serialized != null) {
                try {
                    notifications.add(deserialize(serialized));
                } catch (Exception e) {
                    log.error("Failed to deserialize notification: {}", serialized, e);
                }
            }
        }

        if (!notifications.isEmpty()) {
            try {
                List<TeacherNotification> saved = teacherNotificationRepository.saveAll(notifications);
                log.info("Flushed {} notifications to database", saved.size());
                return saved;
            } catch (Exception e) {
                log.error("Failed to flush notifications to database. Re-buffering {} notifications.",
                        notifications.size(), e);
                // Re-buffer on failure
                for (TeacherNotification n : notifications) {
                    redisTemplate.opsForList().rightPush(BUFFER_KEY, serialize(n));
                }
                return List.of();
            }
        }

        return List.of();
    }

    @Override
    public long getBufferSize() {
        Long size = redisTemplate.opsForList().size(BUFFER_KEY);
        return size != null ? size : 0;
    }

    private String serialize(TeacherNotification n) {
        // Format:
        // teacherId||examId||studentId||studentName||violationType||description||violationCount||autoSubmitted||createdAt
        return String.join(SEPARATOR,
                String.valueOf(n.getTeacherId()),
                String.valueOf(n.getExamId()),
                String.valueOf(n.getStudentId()),
                n.getStudentName() != null ? n.getStudentName() : "",
                n.getViolationType(),
                n.getDescription() != null ? n.getDescription() : "",
                String.valueOf(n.getViolationCount()),
                String.valueOf(n.isAutoSubmitted()),
                n.getCreatedAt() != null ? n.getCreatedAt().format(DT_FORMATTER)
                        : LocalDateTime.now().format(DT_FORMATTER));
    }

    private TeacherNotification deserialize(String serialized) {
        String[] parts = serialized.split("\\|\\|", -1);
        return TeacherNotification.builder()
                .teacherId(Long.parseLong(parts[0]))
                .examId(Long.parseLong(parts[1]))
                .studentId(Long.parseLong(parts[2]))
                .studentName(parts[3].isEmpty() ? null : parts[3])
                .violationType(parts[4])
                .description(parts[5].isEmpty() ? null : parts[5])
                .violationCount(Long.parseLong(parts[6]))
                .autoSubmitted(Boolean.parseBoolean(parts[7]))
                .isRead(false)
                .createdAt(LocalDateTime.parse(parts[8], DT_FORMATTER))
                .build();
    }
}
