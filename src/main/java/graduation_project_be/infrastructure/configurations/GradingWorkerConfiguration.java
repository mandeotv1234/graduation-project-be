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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
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

    private static final String AUTOSCALE_CONFIG_KEY = "grading_worker:autoscale:config";

    private final GradingQueueService gradingQueueService;
    private final GradeExamUsecase gradeExamUsecase;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${grading.worker.enabled:true}")
    private boolean workerEnabled;

    @Value("${grading.worker.concurrency:1}")
    private int configuredConcurrency;

    @Value("${grading.worker.max-jobs-per-cycle:3}")
    private int configuredMaxJobsPerCycle;

    @Value("${grading.worker.stale-timeout-seconds:300}")
    private long staleTimeoutSeconds;

    @Value("${grading.worker.auto-scale-enabled:false}")
    private boolean autoScaleEnabled;

    @Value("${grading.worker.min-concurrency:0}")
    private int configuredMinConcurrency;

    @Value("${grading.worker.max-concurrency:0}")
    private int configuredMaxConcurrency;

    @Value("${grading.worker.scale-up-queue-threshold:10}")
    private int scaleUpQueueThreshold;

    @Value("${grading.worker.scale-down-queue-threshold:2}")
    private int scaleDownQueueThreshold;

    @Value("${grading.worker.scale-down-idle-cycles:6}")
    private int scaleDownIdleCycles;

    private final AtomicInteger activeJobs = new AtomicInteger(0);
    private final AtomicInteger currentConcurrency = new AtomicInteger(1);
    private final AtomicInteger workerThreadCounter = new AtomicInteger(0);
    private ExecutorService executorService;
    private int minConcurrency;
    private int maxConcurrency;
    private int defaultMinConcurrency;
    private int defaultMaxConcurrency;
    private int defaultScaleUpQueueThreshold;
    private int defaultScaleDownQueueThreshold;
    private int defaultScaleDownIdleCycles;
    private int maxConcurrencyLimit;
    private int maxJobsPerCycle;
    private int belowScaleDownThresholdCycles;
    private String lastAutoscaleConfigSignature = "";

    @PostConstruct
    void initializeWorkerPool() {
        int baseConcurrency = Math.max(1, configuredConcurrency);
        minConcurrency = configuredMinConcurrency > 0 ? configuredMinConcurrency : baseConcurrency;
        maxConcurrency = configuredMaxConcurrency > 0 ? configuredMaxConcurrency : baseConcurrency;
        if (!autoScaleEnabled) {
            minConcurrency = baseConcurrency;
            maxConcurrency = baseConcurrency;
        }
        minConcurrency = Math.max(1, minConcurrency);
        maxConcurrency = Math.max(minConcurrency, maxConcurrency);
        maxConcurrencyLimit = maxConcurrency;
        defaultMinConcurrency = minConcurrency;
        defaultMaxConcurrency = maxConcurrency;
        currentConcurrency.set(Math.min(Math.max(baseConcurrency, minConcurrency), maxConcurrency));

        maxJobsPerCycle = Math.max(1, configuredMaxJobsPerCycle);
        staleTimeoutSeconds = Math.max(30, staleTimeoutSeconds);
        scaleUpQueueThreshold = Math.max(1, scaleUpQueueThreshold);
        scaleDownQueueThreshold = Math.max(0, Math.min(scaleDownQueueThreshold, scaleUpQueueThreshold - 1));
        scaleDownIdleCycles = Math.max(1, scaleDownIdleCycles);
        defaultScaleUpQueueThreshold = scaleUpQueueThreshold;
        defaultScaleDownQueueThreshold = scaleDownQueueThreshold;
        defaultScaleDownIdleCycles = scaleDownIdleCycles;

        if (!workerEnabled) {
            log.info("Grading worker disabled");
            return;
        }

        executorService = Executors.newFixedThreadPool(maxConcurrency, task -> {
            Thread thread = new Thread(task);
            thread.setName("grading-worker-" + workerThreadCounter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });

        log.info(
                "Grading worker initialized: enabled={}, autoScale={}, concurrency={}, minConcurrency={}, maxConcurrency={}, maxJobsPerCycle={}, scaleUpQueueThreshold={}, scaleDownQueueThreshold={}, scaleDownIdleCycles={}, staleTimeoutSeconds={}",
                workerEnabled, autoScaleEnabled, currentConcurrency.get(), minConcurrency, maxConcurrency,
                maxJobsPerCycle, scaleUpQueueThreshold, scaleDownQueueThreshold, scaleDownIdleCycles,
                staleTimeoutSeconds);
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

        long queueSize = gradingQueueService.size();
        adjustConcurrency(queueSize);

        int concurrency = currentConcurrency.get();
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
            log.info("Grading cycle dispatched: {} jobs, activeJobs={}, concurrency={}, {} remaining in queue",
                    dispatched, activeJobs.get(), currentConcurrency.get(), remaining);
        }
    }

    private void adjustConcurrency(long queueSize) {
        if (!autoScaleEnabled) {
            return;
        }

        refreshAutoscaleConfigFromRedis();

        int current = currentConcurrency.get();
        if (queueSize >= scaleUpQueueThreshold && current < maxConcurrency) {
            int next = current + 1;
            if (currentConcurrency.compareAndSet(current, next)) {
                belowScaleDownThresholdCycles = 0;
                log.info("Grading worker scaled up: queueSize={}, activeJobs={}, concurrency {} -> {}",
                        queueSize, activeJobs.get(), current, next);
            }
            return;
        }

        if (queueSize <= scaleDownQueueThreshold && current > minConcurrency) {
            belowScaleDownThresholdCycles++;
            boolean hasRoomToScaleDown = activeJobs.get() <= current - 1;
            if (belowScaleDownThresholdCycles >= scaleDownIdleCycles && hasRoomToScaleDown) {
                int next = current - 1;
                if (currentConcurrency.compareAndSet(current, next)) {
                    belowScaleDownThresholdCycles = 0;
                    log.info("Grading worker scaled down: queueSize={}, activeJobs={}, concurrency {} -> {}",
                            queueSize, activeJobs.get(), current, next);
                }
            }
            return;
        }

        belowScaleDownThresholdCycles = 0;
    }

    private void refreshAutoscaleConfigFromRedis() {
        try {
            Map<Object, Object> config = redisTemplate.opsForHash().entries(AUTOSCALE_CONFIG_KEY);
            int nextMinConcurrency = parseInt(config.get("minConcurrency"), defaultMinConcurrency);
            int nextMaxConcurrency = parseInt(config.get("maxConcurrency"), defaultMaxConcurrency);
            int nextScaleUpQueueThreshold = parseInt(config.get("scaleUpQueueThreshold"),
                    defaultScaleUpQueueThreshold);
            int nextScaleDownQueueThreshold = parseInt(config.get("scaleDownQueueThreshold"),
                    defaultScaleDownQueueThreshold);
            int nextScaleDownIdleCycles = parseInt(config.get("scaleDownIdleCycles"),
                    defaultScaleDownIdleCycles);

            nextMinConcurrency = Math.min(Math.max(1, nextMinConcurrency), maxConcurrencyLimit);
            nextMaxConcurrency = Math.min(Math.max(nextMinConcurrency, nextMaxConcurrency), maxConcurrencyLimit);
            nextScaleUpQueueThreshold = Math.max(1, nextScaleUpQueueThreshold);
            nextScaleDownQueueThreshold = Math.max(0,
                    Math.min(nextScaleDownQueueThreshold, nextScaleUpQueueThreshold - 1));
            nextScaleDownIdleCycles = Math.max(1, nextScaleDownIdleCycles);

            String signature = nextMinConcurrency + ":" + nextMaxConcurrency + ":" + nextScaleUpQueueThreshold
                    + ":" + nextScaleDownQueueThreshold + ":" + nextScaleDownIdleCycles;
            if (!signature.equals(lastAutoscaleConfigSignature)) {
                log.info(
                        "Grading worker autoscale config: redisKey={}, minConcurrency={}, maxConcurrency={}, scaleUpQueueThreshold={}, scaleDownQueueThreshold={}, scaleDownIdleCycles={}",
                        AUTOSCALE_CONFIG_KEY, nextMinConcurrency, nextMaxConcurrency,
                        nextScaleUpQueueThreshold, nextScaleDownQueueThreshold, nextScaleDownIdleCycles);
                lastAutoscaleConfigSignature = signature;
            }

            minConcurrency = nextMinConcurrency;
            maxConcurrency = nextMaxConcurrency;
            scaleUpQueueThreshold = nextScaleUpQueueThreshold;
            scaleDownQueueThreshold = nextScaleDownQueueThreshold;
            scaleDownIdleCycles = nextScaleDownIdleCycles;
            currentConcurrency.updateAndGet(value -> Math.min(Math.max(value, minConcurrency), maxConcurrency));
        } catch (Exception e) {
            log.warn("Could not refresh grading autoscale config from Redis key {}", AUTOSCALE_CONFIG_KEY, e);
        }
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
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
