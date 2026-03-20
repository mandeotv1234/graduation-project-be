package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.MicrosoftAuthService;
import graduation_project_be.application.usecases.request.MicrosoftLoginRequest;
import graduation_project_be.application.usecases.response.LoginResponse;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MicrosoftLoginUsecase {

    private final UserRepository userRepository;
    private final MicrosoftAuthService microsoftAuthService;
    private final TokenIssuer tokenIssuer;

    public LoginResponse execute(MicrosoftLoginRequest request) {
        try {
            MicrosoftAuthService.MicrosoftUserInfo microsoftUserInfo = microsoftAuthService.verifyMicrosoftToken(
                    request.idToken());

            Optional<User> userOptional = userRepository.findByEmail(microsoftUserInfo.email());

            User user;
            if (userOptional.isEmpty()) {
                user = User.builder()
                        .email(microsoftUserInfo.email())
                        .fullName(microsoftUserInfo.name())
                        .role(Role.STUDENT)
                        .isActive(true)
                        .createdAt(LocalDateTime.now())
                        .build();

                user = userRepository.save(user);
            } else {
                user = userOptional.get();
            }

            return tokenIssuer.issueToken(user);

        } catch (Exception e) {
            throw new UnauthorizedException("Failed to authenticate with Microsoft: " + e.getMessage());
        }
    }
}
