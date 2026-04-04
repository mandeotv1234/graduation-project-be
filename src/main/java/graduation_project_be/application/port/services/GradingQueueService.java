package graduation_project_be.application.port.services;

/**
 * Port interface for the grading queue.
 * Abstracts the message queue mechanism (Redis List, RabbitMQ, etc.)
 * so the application layer remains framework-agnostic.
 */
public interface GradingQueueService {

    /**
     * Enqueue a grading job identified by examId + studentId + attemptNumber.
     * The implementation should serialize these into a queue entry.
     */
    void enqueue(Long examId, Long studentId, int attemptNumber);

    /**
     * Dequeue the next grading job from the queue and move to processing area.
     * Returns null if the queue is empty.
     */
    GradingJob dequeue();

    /**
     * Acknowledge successfully processing. Message is removed from the processing
     * area.
     */
    void ack(GradingJob job);

    /**
     * Negative Acknowledge. Put message in DLQ if retry >= 2 (after 3 attempts
     * total), else re-enqueue.
     * Returns true if sent to DLQ, false if re-enqueued.
     */
    boolean nack(GradingJob job);

    /**
     * Check processing area for stale jobs (e.g., worker crashed) and move them
     * back to main queue.
     */
    void recoverStaleJobs();

    /**
     * Get the current queue size.
     */
    long size();

    /**
     * Immutable record representing a grading job.
     */
    record GradingJob(Long examId, Long studentId, int attemptNumber, int retryCount) {
        public String toRedisValue() {
            return examId + ":" + studentId + ":" + attemptNumber + ":" + retryCount;
        }

        public GradingJob withIncrementedRetry() {
            return new GradingJob(examId, studentId, attemptNumber, retryCount + 1);
        }
    }
}
