package graduation_project_be.infrastructure.configurations;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async thread-pool configuration.
 *
 * <p>Provides a shared fixed-thread-pool used for parallel Gemini API calls
 * during PDF export lazy generation of spec-entity descriptions.
 */
@Configuration
public class AsyncConfiguration {

    @Bean(name = "geminiExecutor", destroyMethod = "shutdown")
    ExecutorService geminiExecutor() {
        return Executors.newFixedThreadPool(4);
    }
}
