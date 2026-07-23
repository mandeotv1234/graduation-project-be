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

    private static final String VNG_TEST_TEACHER_EMAIL = "manh@vng.com.vn";

    private final UserRepository userRepository;
    private final GoogleAuthService googleAuthService;
    private final TokenIssuer tokenIssuer;

    public LoginResponse execute(GoogleLoginRequest request) {
        try {
            GoogleAuthService.GoogleUserInfo googleUserInfo = googleAuthService.verifyGoogleToken(
                    request.code(),
                    request.redirectUri());

            String normalizedEmail = googleUserInfo.email().trim().toLowerCase();
            if (!normalizedEmail.endsWith("@fit.hcmus.edu.vn")
                    && !VNG_TEST_TEACHER_EMAIL.equals(normalizedEmail)) {
                throw new UnauthorizedException(
                        "Email Google không thuộc danh sách được phép đăng nhập");
            }

            Optional<User> userOptional = userRepository.findByEmail(normalizedEmail);

            User user;
            if (userOptional.isEmpty()) {
                user = User.builder()
                        .email(normalizedEmail)
                        .fullName(normalizeProviderName(googleUserInfo.name()))
                        .googleSubject(googleUserInfo.subject())
                        .role(Role.TEACHER)
                        .isActive(true)
                        .createdAt(TimeUtils.now())
                        .build();

                user = userRepository.save(user);
            } else {
                user = userOptional.get();
                boolean shouldSave = false;

                if (user.getGoogleSubject() == null) {
                    user.setGoogleSubject(googleUserInfo.subject());
                    shouldSave = true;
                }

                String googleName = normalizeProviderName(googleUserInfo.name());
                if (googleName != null && !googleName.equals(user.getFullName())) {
                    user.setFullName(googleName);
                    shouldSave = true;
                }

                if (shouldSave) {
                    user = userRepository.save(user);
                }
            }

            return tokenIssuer.issueToken(user);

        } catch (Exception e) {
            throw new UnauthorizedException("Failed to authenticate with Google: " + e.getMessage());
        }
    }

    private String normalizeProviderName(String providerName) {
        return providerName == null || providerName.isBlank() ? null : providerName.trim();
    }
}
