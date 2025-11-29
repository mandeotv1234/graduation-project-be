package graduation_project_be.shared.infrastructure.configuration;


import graduation_project_be.user.application.port.UserRepository;
import graduation_project_be.auth.application.port.JwtService;
import graduation_project_be.auth.application.port.PasswordEncoder;
import graduation_project_be.auth.application.usecase.LoginUsecase;
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
