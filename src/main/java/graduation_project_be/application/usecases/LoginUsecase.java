package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.UserRepository;
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
    private final TokenIssuer tokenIssuer;

    public LoginResponse execute(LoginRequest loginRequest) {

        Optional<User> userOptional = userRepository.findByEmail(loginRequest.email());

        if (userOptional.isEmpty()) {
            throw new UnauthorizedException("Invalid email or password");
        }

        String storedPassword = userOptional.get().getPassword();
        if (storedPassword == null || !passwordEncoder.matches(loginRequest.password(), storedPassword)) {
            throw new UnauthorizedException("Invalid email or password");
        }

        return tokenIssuer.issueToken(userOptional.get());
    }
}