
package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.application.port.services.PasswordEncoder;
import graduation_project_be.infrastructure.security.JwtAuthenticationFilter;
import graduation_project_be.infrastructure.services.JwtServiceImpl;
import graduation_project_be.infrastructure.services.PasswordEncoderImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    @Value("${spring.application.security.jwt.expiration}")
    private Integer jwtTokenValidity;
    @Value("${spring.application.security.refresh-token.expiration}")
    private Integer jwtRefreshTokenValidity;
    @Value("${spring.application.security.jwt.secret-key}")
    private String secretKey;

    @Bean
    JwtService jwtService() {
        return new JwtServiceImpl(jwtTokenValidity, jwtRefreshTokenValidity, secretKey);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                // This is a pure REST API — disable Spring Security's default X-Frame-Options
                // and XSS-Protection headers since they only apply to HTML documents.
                // The FE handles frame restrictions via its own CSP (frame-ancestors 'none').
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
                        .xssProtection(HeadersConfigurer.XXssConfig::disable))
                .authorizeHttpRequests(
                        auth -> auth.requestMatchers(
                                "/api/auth/**",
                                "/ws/**")
                                .permitAll()
                                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                                .anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Qualifier("frontendUrl") String frontendUrl) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontendUrl));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowCredentials(true);
        // Required so FE fetch/XHR can read the filename from Content-Disposition
        config.setExposedHeaders(List.of("Content-Disposition"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new PasswordEncoderImpl(new BCryptPasswordEncoder());
    }
}