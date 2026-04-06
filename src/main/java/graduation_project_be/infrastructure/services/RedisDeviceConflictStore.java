package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import graduation_project_be.application.port.services.DeviceConflictStore;
import graduation_project_be.domain.models.ExamDeviceConflict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@Slf4j
public class RedisDeviceConflictStore implements DeviceConflictStore {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration CONFLICT_TTL = Duration.ofMinutes(5);
    // Key by conflictId for direct lookup
    private static final String CONFLICT_KEY_PREFIX = "device_conflict:";
    // Secondary index key: exam+student → conflictId (for dedup)
    private static final String CONFLICT_INDEX_PREFIX = "device_conflict_idx:";

    public RedisDeviceConflictStore(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    private String buildKey(String conflictId) {
        return CONFLICT_KEY_PREFIX + conflictId;
    }

    private String buildIndexKey(Long examId, Long studentId) {
        return String.format("%s%d:%d", CONFLICT_INDEX_PREFIX, examId, studentId);
    }

    @Override
    public void save(ExamDeviceConflict conflict) {
        try {
            String json = objectMapper.writeValueAsString(conflict);
            String key = buildKey(conflict.getConflictId());
            String indexKey = buildIndexKey(conflict.getExamId(), conflict.getStudentId());

            // Delete old conflict for same exam+student (dedup)
            String oldConflictId = redisTemplate.opsForValue().get(indexKey);
            if (oldConflictId != null) {
                redisTemplate.delete(buildKey(oldConflictId));
            }

            redisTemplate.opsForValue().set(key, json, CONFLICT_TTL);
            redisTemplate.opsForValue().set(indexKey, conflict.getConflictId(), CONFLICT_TTL);
            log.info("Device conflict saved: conflictId={}, exam={}, student={}",
                    conflict.getConflictId(), conflict.getExamId(), conflict.getStudentId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize device conflict: {}", e.getMessage());
        }
    }

    @Override
    public Optional<ExamDeviceConflict> findByConflictId(String conflictId) {
        String key = buildKey(conflictId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ExamDeviceConflict.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize device conflict: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String conflictId) {
        Optional<ExamDeviceConflict> conflictOpt = findByConflictId(conflictId);
        conflictOpt.ifPresent(c -> {
            String indexKey = buildIndexKey(c.getExamId(), c.getStudentId());
            redisTemplate.delete(Set.of(buildKey(conflictId), indexKey));
        });
        if (conflictOpt.isEmpty()) {
            redisTemplate.delete(buildKey(conflictId));
        }
        log.info("Device conflict deleted: conflictId={}", conflictId);
    }
}
