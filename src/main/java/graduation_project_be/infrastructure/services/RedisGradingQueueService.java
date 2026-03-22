package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.GradingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis List-based implementation of the grading queue.
 *
 * Uses RPUSH to enqueue grading jobs and LPOP to dequeue them (FIFO order).
 * This ensures no message loss — if the worker crashes, unprocessed jobs
 * remain safely in the Redis List until they are consumed.
 *
 * Key format: "grading_queue" (single queue for all grading jobs)
 * Value format: "examId:studentId:attemptNumber" (simple string serialization)
 */
@Slf4j
@RequiredArgsConstructor
public class RedisGradingQueueService implements GradingQueueService {

    private static final String QUEUE_KEY = "grading_queue";

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void enqueue(Long examId, Long studentId, int attemptNumber) {
        String value = examId + ":" + studentId + ":" + attemptNumber;
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
            String[] parts = value.split(":");
            Long examId = Long.parseLong(parts[0]);
            Long studentId = Long.parseLong(parts[1]);
            int attemptNumber = Integer.parseInt(parts[2]);
            log.debug("Dequeued grading job: exam={}, student={}, attempt={}", examId, studentId, attemptNumber);
            return new GradingJob(examId, studentId, attemptNumber);
        } catch (Exception e) {
            log.error("Failed to parse grading job from queue: '{}'. Discarding.", value, e);
            return null;
        }
    }

    @Override
    public long size() {
        Long len = redisTemplate.opsForList().size(QUEUE_KEY);
        return len != null ? len : 0;
    }
}
