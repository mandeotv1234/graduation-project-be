package graduation_project_be.infrastructure.persistence.repositories.redis;

import graduation_project_be.application.port.repositories.RefreshTokenRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;

public class RedisRefreshTokenRepository implements RefreshTokenRepository {

    private final RedisTemplate<String, String> redisTemplate;

    public RedisRefreshTokenRepository(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private String key(String userId, String tokenId) {
        return String.format("rt:%s:%s", userId, tokenId);
    }

    @Override
    public void save(String userId, String tokenId, String hashedToken, long ttlSeconds) {
        String k = key(userId, tokenId);
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        ops.set(k, hashedToken, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public String find(String userId, String tokenId) {
        String k = key(userId, tokenId);
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        return ops.get(k);
    }

    @Override
    public void delete(String userId, String tokenId) {
        String k = key(userId, tokenId);
        redisTemplate.delete(k);
    }

    @Override
    public void deleteAllByUserId(String userId) {
        String pattern = String.format("rt:%s:*", userId);
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}

