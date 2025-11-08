package graduation_project_be.infrastructure.configurations;


import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.application.port.services.PasswordEncoder;
import graduation_project_be.application.usecases.LoginUsecase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class UsecasesConfiguration {

    @Bean
    LoginUsecase authenticationUsecase(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService
    ) {
        return new LoginUsecase(
            userRepository,
            passwordEncoder,
            jwtService
        );
    }

}
