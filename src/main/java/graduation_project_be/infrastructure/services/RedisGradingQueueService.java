package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.GradingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis Reliable Queue implementation of the grading queue.
 *
 * Uses grading_queue (List) and grading_processing (ZSet) for manual ACK.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisGradingQueueService implements GradingQueueService {

    private static final String QUEUE_KEY = "grading_queue";
    private static final String PROCESSING_KEY = "grading_processing";
    private static final String DLQ_KEY = "grading_dlq";

    private static final String DEQUEUE_SCRIPT =
            "local item = redis.call('LPOP', KEYS[1]) " +
            "if item then " +
            "    redis.call('ZADD', KEYS[2], ARGV[1], item) " +
            "    return item " +
            "end " +
            "return nil";

    private static final String RECOVER_SCRIPT =
            "local staleItems = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) " +
            "for i, item in ipairs(staleItems) do " +
            "    redis.call('ZREM', KEYS[1], item) " +
            "    redis.call('RPUSH', KEYS[2], item) " +
            "end " +
            "return staleItems";

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void enqueue(Long examId, Long studentId, int attemptNumber) {
        GradingJob job = new GradingJob(examId, studentId, attemptNumber, 0);
        String value = job.toRedisValue();
        redisTemplate.opsForList().rightPush(QUEUE_KEY, value);
        log.info("Enqueued grading job: {}", value);
    }

    @Override
    public GradingJob dequeue() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>(DEQUEUE_SCRIPT, String.class);
        String value = redisTemplate.execute(script, List.of(QUEUE_KEY, PROCESSING_KEY), String.valueOf(System.currentTimeMillis()));

        if (value == null) {
            return null;
        }

        try {

            String[] parts = value.split(":");
            Long examId = Long.parseLong(parts[0]);
            Long studentId = Long.parseLong(parts[1]);
            int attemptNumber = Integer.parseInt(parts[2]);
            int retryCount = parts.length > 3 ? Integer.parseInt(parts[3]) : 0;
            
            log.debug("Dequeued grading job: exam={}, student={}, attempt={}, retry={}", examId, studentId, attemptNumber, retryCount);
            return new GradingJob(examId, studentId, attemptNumber, retryCount);
        } catch (Exception e) {
            log.error("Failed to parse grading job from queue: '{}'. Discarding.", value, e);
            redisTemplate.opsForZSet().remove(PROCESSING_KEY, value);
            return null;
        }
    }

    @Override
    public void ack(GradingJob job) {
        String value = job.toRedisValue();
        redisTemplate.opsForZSet().remove(PROCESSING_KEY, value);
        log.debug("ACKed grading job: {}", value);
    }

    @Override
    public boolean nack(GradingJob job) {
        String oldValue = job.toRedisValue();
        redisTemplate.opsForZSet().remove(PROCESSING_KEY, oldValue);
        
        if (job.retryCount() >= 2) { // 3rd try failed -> DLQ
            redisTemplate.opsForList().rightPush(DLQ_KEY, oldValue);
            log.error("Grading job failed 3 times, moved to DLQ: {}", oldValue);
            return true;
        } else {
            String newValue = job.withIncrementedRetry().toRedisValue();
            redisTemplate.opsForList().rightPush(QUEUE_KEY, newValue);
            log.warn("NACKed grading job, requeued for retry: {}", newValue);
            return false;
        }
    }

    @Override
    public void recoverStaleJobs() {
        long staleThresholdMs = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5);
        @SuppressWarnings("rawtypes")
        DefaultRedisScript<List> script = new DefaultRedisScript<>(RECOVER_SCRIPT, List.class);
        
        @SuppressWarnings("unchecked")
        List<String> recoveredItems = (List<String>) redisTemplate.execute(script, List.of(PROCESSING_KEY, QUEUE_KEY), String.valueOf(staleThresholdMs));
        
        if (recoveredItems != null && !recoveredItems.isEmpty()) {
            for (String value : recoveredItems) {
                log.warn("Recovered stale grading job (Worker Crash detected): {}", value);
            }
        }
    }

    @Override
    public long size() {
        Long len = redisTemplate.opsForList().size(QUEUE_KEY);
        return len != null ? len : 0;
    }
}
