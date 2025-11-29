package graduation_project_be.auth.application.usecase;

import graduation_project_be.shared.application.exception.UnauthorizedException;
import graduation_project_be.user.application.port.UserRepository;
import graduation_project_be.auth.application.port.JwtService;
import graduation_project_be.auth.application.port.PasswordEncoder;
import graduation_project_be.user.domain.User;
import lombok.Builder;
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

        String token = jwtService.generateToken(userOptional.get());

        return new LoginResponse(token);
    }

    public record LoginRequest(String email, String password) {}

    public record LoginResponse(String accessToken) {}
}