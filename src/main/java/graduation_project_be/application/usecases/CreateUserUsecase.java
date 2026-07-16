package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ConflictException;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.PasswordEncoder;
import graduation_project_be.application.usecases.request.CreateUserRequest;
import graduation_project_be.application.usecases.response.CreateUserResponse;
import graduation_project_be.domain.models.User;
import graduation_project_be.shared.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class CreateUserUsecase {

    static final String DEFAULT_PASSWORD = "Vlchinsu1234*";
    private static final Pattern FIT_EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@fit\\.hcmus\\.edu\\.vn$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public CreateUserResponse execute(CreateUserRequest request) {
        if (request == null || request.role() == null) {
            throw new BadRequestException("Role is required");
        }

        String email = normalizeEmail(request.email());
        if (!isFitEmail(email)) {
            throw new BadRequestException("Email must belong to @fit.hcmus.edu.vn");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("User", "email", email);
        }

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(DEFAULT_PASSWORD))
                .fullName(email.substring(0, email.indexOf('@')))
                .role(request.role())
                .isActive(true)
                .createdAt(TimeUtils.now())
                .build();

        return CreateUserResponse.fromModel(userRepository.save(user));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isFitEmail(String email) {
        return FIT_EMAIL_PATTERN.matcher(email).matches();
    }
}
