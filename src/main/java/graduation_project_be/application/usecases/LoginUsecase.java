package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.application.port.services.PasswordEncoder;
import graduation_project_be.application.usecases.request.LoginRequest;
import graduation_project_be.application.usecases.response.LoginResponse;
import graduation_project_be.domain.models.User;
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

        if (!passwordEncoder.matches(loginRequest.password(),userOptional.get().getPassword())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        User user = userOptional.get();
        String accessToken = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return LoginResponse.fromTokens(accessToken, refreshToken);
    }



}