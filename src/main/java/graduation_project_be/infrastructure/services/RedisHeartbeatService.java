package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.HeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class RedisHeartbeatService implements HeartbeatService {

    private static final String KEY_PREFIX = "heartbeat:";
    // TTL must outlive maxHeartbeatGapSec so the absence sweep can still see a stale key and raise a violation.
    private static final Duration HEARTBEAT_TTL = Duration.ofHours(4);

    private final RedisTemplate<String, String> redisTemplate;

    private String buildKey(Long examId, Long studentId) {
        return KEY_PREFIX + examId + ":" + studentId;
    }

    @Override
    public Optional<HeartbeatState> get(Long examId, Long studentId) {
        return parse(redisTemplate.opsForValue().get(buildKey(examId, studentId)));
    }

    @Override
    public void save(Long examId, Long studentId, HeartbeatState state) {
        String value = state.lastSeenEpochMs() + "|" + state.lastSeq() + "|"
                + state.tamperStreak() + "|" + (state.flagged() ? 1 : 0);
        redisTemplate.opsForValue().set(buildKey(examId, studentId), value, HEARTBEAT_TTL);
    }

    @Override
    public void clear(Long examId, Long studentId) {
        redisTemplate.delete(buildKey(examId, studentId));
    }

    @Override
    public List<HeartbeatKey> scanActive() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        List<HeartbeatKey> result = new ArrayList<>();
        if (keys == null) return result;
        for (String key : keys) {
            String[] parts = key.split(":");
            if (parts.length >= 3) {
                try {
                    result.add(new HeartbeatKey(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
                } catch (NumberFormatException e) {
                    log.warn("Malformed heartbeat key skipped: {}", key);
                }
            }
        }
        return result;
    }

    private Optional<HeartbeatState> parse(String value) {
        if (value == null) return Optional.empty();
        String[] parts = value.split("\\|");
        if (parts.length < 4) return Optional.empty();
        try {
            return Optional.of(new HeartbeatState(
                    Long.parseLong(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    "1".equals(parts[3])));
        } catch (NumberFormatException e) {
            log.warn("Malformed heartbeat value skipped: {}", value);
            return Optional.empty();
        }
    }
}
