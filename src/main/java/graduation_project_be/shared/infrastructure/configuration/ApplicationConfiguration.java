package graduation_project_be.shared.infrastructure.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfiguration {
    @Value("${spring.application.url}")
    private String baseUrl;

    @Bean
    public String frontendUrl() {
        return baseUrl;
    }

    @Bean
    public String backendUrl() {
        return baseUrl + "/api";
    }
}
