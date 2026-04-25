package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.GoogleAuthService;
import graduation_project_be.application.usecases.request.GoogleLoginRequest;
import graduation_project_be.application.usecases.response.LoginResponse;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class GoogleLoginUsecase {

    private final UserRepository userRepository;
    private final GoogleAuthService googleAuthService;
    private final TokenIssuer tokenIssuer;

    public LoginResponse execute(GoogleLoginRequest request) {
        try {
            GoogleAuthService.GoogleUserInfo googleUserInfo = googleAuthService.verifyGoogleToken(
                    request.code(),
                    request.redirectUri());

             if (!googleUserInfo.email().endsWith("@fit.hcmus.edu.vn")) {
                 throw new UnauthorizedException("Chỉ cho phép sử dụng email thuộc @fit.hcmus.edu.vn để đăng nhập");
             }

            Optional<User> userOptional = userRepository.findByEmail(googleUserInfo.email());

            User user;
            if (userOptional.isEmpty()) {
                user = User.builder()
                        .email(googleUserInfo.email())
                        .fullName(googleUserInfo.name())
                        .googleSubject(googleUserInfo.subject())
                        .role(Role.TEACHER)
                        .isActive(true)
                        .createdAt(TimeUtils.now())
                        .build();

                user = userRepository.save(user);
            } else {
                user = userOptional.get();

                if (user.getGoogleSubject() == null) {
                    user.setGoogleSubject(googleUserInfo.subject());
                    user = userRepository.save(user);
                }
            }

            return tokenIssuer.issueToken(user);

        } catch (Exception e) {
            throw new UnauthorizedException("Failed to authenticate with Google: " + e.getMessage());
        }
    }
}
