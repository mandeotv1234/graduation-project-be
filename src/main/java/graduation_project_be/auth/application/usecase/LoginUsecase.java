package graduation_project_be.auth.application.usecase;

import graduation_project_be.auth.application.port.JwtService;
import graduation_project_be.auth.application.port.PasswordEncoder;
import graduation_project_be.auth.application.usecase.dtos.request.LoginRequest;
import graduation_project_be.auth.application.usecase.dtos.response.LoginResponse;
import graduation_project_be.shared.application.exception.UnauthorizedException;
import graduation_project_be.user.application.port.UserRepository;
import graduation_project_be.user.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class LoginUsecase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginResponse execute(LoginRequest loginRequest) {

        Optional<User> userOptional = userRepository.findByEmail(loginRequest.email());

        if (userOptional.isEmpty()) {
            throw new UnauthorizedException("Invalid email or password");
        }

        if (!passwordEncoder.matches(loginRequest.password(), userOptional.get().getPassword())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        User user = userOptional.get();
        String accessToken = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return LoginResponse.fromTokens(accessToken, refreshToken);
    }
}
