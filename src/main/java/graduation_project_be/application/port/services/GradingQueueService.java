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
     * Dequeue the next grading job from the queue.
     * Returns null if the queue is empty.
     */
    GradingJob dequeue();

    /**
     * Get the current queue size.
     */
    long size();

    /**
     * Immutable record representing a grading job.
     */
    record GradingJob(Long examId, Long studentId, int attemptNumber) {
    }
}
