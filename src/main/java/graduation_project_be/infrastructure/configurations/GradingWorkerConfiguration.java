package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.port.services.GradingQueueService;
import graduation_project_be.application.port.services.GradingQueueService.GradingJob;
import graduation_project_be.application.usecases.GradeExamUsecase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Background grading worker configuration.
 *
 * Polls the Redis grading queue every 2 seconds.
 * When a job is found, it invokes GradeExamUsecase to perform the actual
 * grading.
 *
 * Concurrency is naturally limited to 1 worker per poll cycle.
 * For higher concurrency, increase the number of jobs processed per cycle
 * (see MAX_JOBS_PER_CYCLE) or use a thread pool.
 *
 * This approach is deliberately simple for a graduation project:
 * - No external message broker required
 * - Easy to debug and monitor
 * - Graceful under VM 4GB RAM constraints
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableAsync
@RequiredArgsConstructor
public class GradingWorkerConfiguration {

    /** Maximum number of grading jobs to process per polling cycle */
    private static final int MAX_JOBS_PER_CYCLE = 3;

    private final GradingQueueService gradingQueueService;
    private final GradeExamUsecase gradeExamUsecase;

    /**
     * Polls the Redis grading queue every 2 seconds.
     * Processes up to MAX_JOBS_PER_CYCLE jobs per cycle to control MSSQL load.
     */
    @Scheduled(fixedDelay = 2000)
    public void pollGradingQueue() {
        int processed = 0;

        while (processed < MAX_JOBS_PER_CYCLE) {
            GradingJob job = gradingQueueService.dequeue();
            if (job == null) {
                break; // Queue is empty
            }

            try {
                log.info("Processing grading job {}/{}: exam={}, student={}, attempt={}, retry={}",
                        processed + 1, MAX_JOBS_PER_CYCLE, job.examId(), job.studentId(), job.attemptNumber(),
                        job.retryCount());

                gradeExamUsecase.execute(job.examId(), job.studentId(), job.attemptNumber());

                // Manual ACK upon successfully processing
                gradingQueueService.ack(job);
                processed++;

            } catch (Exception e) {
                log.error("Grading job crashed with exception: exam={}, student={}, attempt={}: {}",
                        job.examId(), job.studentId(), job.attemptNumber(), e.getMessage(), e);

                // NACK job and check if it went to DLQ
                boolean sentToDlq = gradingQueueService.nack(job);
                if (sentToDlq) {
                    try {
                        gradeExamUsecase.markSystemError(job.examId(), job.studentId(), job.attemptNumber());
                    } catch (Exception ex) {
                        log.error("Failed to mark system error on DB", ex);
                    }
                }

                processed++;
            }
        }

        if (processed > 0) {
            long remaining = gradingQueueService.size();
            log.info("Grading cycle completed: {} jobs processed, {} remaining in queue", processed, remaining);
        }
    }

    /**
     * Runs every 1 minute to check for jobs stuck in the processing queue
     * (e.g. if the worker JVM was forcefully killed/OOM during processing)
     */
    @Scheduled(fixedRate = 60000)
    public void recoverStaleJobs() {
        try {
            gradingQueueService.recoverStaleJobs();
        } catch (Exception e) {
            log.error("Error during stale grading job recovery", e);
        }
    }
}
