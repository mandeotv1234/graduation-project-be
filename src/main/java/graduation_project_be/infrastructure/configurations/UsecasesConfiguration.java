package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.repositories.RefreshTokenRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.application.port.services.PasswordEncoder;
import graduation_project_be.application.port.services.RefreshTokenHasher;
import graduation_project_be.application.usecases.CreateClassUsecase;
import graduation_project_be.application.usecases.LoginUsecase;
import graduation_project_be.application.usecases.RefreshUsecase;
import graduation_project_be.application.usecases.LogoutUsecase;
import graduation_project_be.application.usecases.GetClassesUsecase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UsecasesConfiguration {

    @Bean
    CreateClassUsecase createClassUsecase(
            ClassRepository classRepository,
            UserRepository userRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            PasswordEncoder passwordEncoder,
            CurrentUserService currentUserService) {
        return new CreateClassUsecase(classRepository, userRepository, classEnrollmentRepository, passwordEncoder,
                currentUserService);
    }

    @Bean
    LoginUsecase authenticationUsecase(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenHasher refreshTokenHasher) {
        return new LoginUsecase(
                userRepository,
                passwordEncoder,
                jwtService,
                refreshTokenRepository,
                refreshTokenHasher);
    }

    @Bean
    RefreshUsecase refreshUsecase(
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            RefreshTokenHasher refreshTokenHasher) {
        return new RefreshUsecase(refreshTokenRepository, jwtService, refreshTokenHasher);
    }

    @Bean
    LogoutUsecase logoutUsecase(
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            RefreshTokenHasher refreshTokenHasher) {
        return new LogoutUsecase(refreshTokenRepository, jwtService, refreshTokenHasher);
    }

    @Bean
    GetClassesUsecase getClassesUsecase(
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new GetClassesUsecase(classRepository, currentUserService);
    }

}
