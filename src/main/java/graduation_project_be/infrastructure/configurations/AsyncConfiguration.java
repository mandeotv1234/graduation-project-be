package graduation_project_be.infrastructure.configurations;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

/**
 * Async thread-pool configuration.
 *
 * <p>Provides a shared fixed-thread-pool used for parallel AI API calls
 * during PDF export lazy generation of spec-entity descriptions.
 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

    @Bean(name = "aiExecutor", destroyMethod = "shutdown")
    ExecutorService aiExecutor() {
        return Executors.newFixedThreadPool(4);
    }

    @Bean(name = "mailExecutor")
    Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mail-");
        executor.initialize();
        return executor;
    }
}
