package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.port.services.GradingQueueService;
import graduation_project_be.application.port.services.GradingQueueService.GradingJob;
import graduation_project_be.application.usecases.GradeExamUsecase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final GradingQueueService gradingQueueService;
    private final GradeExamUsecase gradeExamUsecase;

    @Value("${grading.worker.enabled:true}")
    private boolean workerEnabled;

    @Value("${grading.worker.concurrency:1}")
    private int configuredConcurrency;

    @Value("${grading.worker.max-jobs-per-cycle:3}")
    private int configuredMaxJobsPerCycle;

    @Value("${grading.worker.stale-timeout-seconds:300}")
    private long staleTimeoutSeconds;

    private final AtomicInteger activeJobs = new AtomicInteger(0);
    private final AtomicInteger workerThreadCounter = new AtomicInteger(0);
    private ExecutorService executorService;
    private int concurrency;
    private int maxJobsPerCycle;

    @PostConstruct
    void initializeWorkerPool() {
        concurrency = Math.max(1, configuredConcurrency);
        maxJobsPerCycle = Math.max(1, configuredMaxJobsPerCycle);
        staleTimeoutSeconds = Math.max(30, staleTimeoutSeconds);

        if (!workerEnabled) {
            log.info("Grading worker disabled");
            return;
        }

        executorService = Executors.newFixedThreadPool(concurrency, task -> {
            Thread thread = new Thread(task);
            thread.setName("grading-worker-" + workerThreadCounter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });

        log.info("Grading worker initialized: enabled={}, concurrency={}, maxJobsPerCycle={}, staleTimeoutSeconds={}",
                workerEnabled, concurrency, maxJobsPerCycle, staleTimeoutSeconds);
    }

    @PreDestroy
    void shutdownWorkerPool() {
        if (executorService == null) {
            return;
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
    }

    /**
     * Polls the Redis grading queue every 2 seconds.
     * Dispatches up to the configured available worker capacity to control MSSQL load.
     */
    @Scheduled(fixedDelay = 2000)
    public void pollGradingQueue() {
        if (!workerEnabled) {
            return;
        }

        int availableSlots = concurrency - activeJobs.get();
        if (availableSlots <= 0) {
            log.debug("Grading worker at capacity: activeJobs={}, concurrency={}", activeJobs.get(), concurrency);
            return;
        }

        int jobsToDispatch = Math.min(maxJobsPerCycle, availableSlots);
        int dispatched = 0;

        while (dispatched < jobsToDispatch) {
            GradingJob job = gradingQueueService.dequeue();
            if (job == null) {
                break; // Queue is empty
            }

            activeJobs.incrementAndGet();
            dispatched++;
            try {
                executorService.submit(() -> processJob(job));
            } catch (RuntimeException e) {
                activeJobs.decrementAndGet();
                log.error("Failed to dispatch grading job: exam={}, student={}, attempt={}: {}",
                        job.examId(), job.studentId(), job.attemptNumber(), e.getMessage(), e);
                gradingQueueService.nack(job);
            }
        }

        if (dispatched > 0) {
            long remaining = gradingQueueService.size();
            log.info("Grading cycle dispatched: {} jobs, activeJobs={}, {} remaining in queue",
                    dispatched, activeJobs.get(), remaining);
        }
    }

    private void processJob(GradingJob job) {
        try {
            log.info("Processing grading job: exam={}, student={}, attempt={}, retry={}",
                    job.examId(), job.studentId(), job.attemptNumber(), job.retryCount());

            gradeExamUsecase.execute(job.examId(), job.studentId(), job.attemptNumber());

            // Manual ACK upon successfully processing
            gradingQueueService.ack(job);
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
        } finally {
            activeJobs.decrementAndGet();
        }
    }

    /**
     * Runs every 1 minute to check for jobs stuck in the processing queue
     * (e.g. if the worker JVM was forcefully killed/OOM during processing)
     */
    @Scheduled(fixedRate = 60000)
    public void recoverStaleJobs() {
        if (!workerEnabled) {
            return;
        }
        try {
            gradingQueueService.recoverStaleJobs(staleTimeoutSeconds);
        } catch (Exception e) {
            log.error("Error during stale grading job recovery", e);
        }
    }
}
