package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.port.services.NotificationBufferService;
import graduation_project_be.application.port.services.ViolationNotificationService;
import graduation_project_be.infrastructure.services.WebSocketViolationNotificationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private final String frontendUrl;

    public WebSocketConfiguration(@Qualifier("frontendUrl") String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Client subscribes to /topic/... and /queue/...
        config.enableSimpleBroker("/topic", "/queue");
        // Client sends to /app/...
        config.setApplicationDestinationPrefixes("/app");
        // User-specific destination prefix
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(frontendUrl)
                .withSockJS();
    }

    @Bean
    public ViolationNotificationService violationNotificationService(
            SimpMessagingTemplate messagingTemplate,
            NotificationBufferService notificationBufferService) {
        return new WebSocketViolationNotificationService(messagingTemplate, notificationBufferService);
    }
}
