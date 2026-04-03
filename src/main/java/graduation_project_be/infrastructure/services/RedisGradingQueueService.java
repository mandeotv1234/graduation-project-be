package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.GradingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Set;
import java.util.concurrent.TimeUnit;

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
        String value = redisTemplate.opsForList().leftPop(QUEUE_KEY);
        if (value == null) {
            return null;
        }

        try {
            // Put in processing ZSet with current timestamp as score
            redisTemplate.opsForZSet().add(PROCESSING_KEY, value, System.currentTimeMillis());

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
        // Find jobs in processing queue older than 5 minutes
        long staleThresholdMs = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5);
        Set<String> staleJobs = redisTemplate.opsForZSet().rangeByScore(PROCESSING_KEY, 0, staleThresholdMs);
        
        if (staleJobs != null && !staleJobs.isEmpty()) {
            for (String value : staleJobs) {
                log.warn("Recovering stale grading job (Worker Crash detected): {}", value);
                // Atomically remove and re-enqueue
                redisTemplate.opsForZSet().remove(PROCESSING_KEY, value);
                redisTemplate.opsForList().rightPush(QUEUE_KEY, value);
            }
        }
    }

    @Override
    public long size() {
        Long len = redisTemplate.opsForList().size(QUEUE_KEY);
        return len != null ? len : 0;
    }
}
