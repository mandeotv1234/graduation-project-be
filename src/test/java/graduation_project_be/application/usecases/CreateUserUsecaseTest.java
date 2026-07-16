package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ConflictException;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.PasswordEncoder;
import graduation_project_be.application.usecases.request.CreateUserRequest;
import graduation_project_be.application.usecases.response.CreateUserResponse;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateUserUsecaseTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private CreateUserUsecase createUserUsecase;

    @BeforeEach
    void setUp() {
        createUserUsecase = new CreateUserUsecase(userRepository, passwordEncoder);
    }

    @Test
    void execute_should_create_active_user_with_normalized_email_and_hashed_default_password() {
        String encodedPassword = "$2a$10$encoded-password";
        when(userRepository.findByEmail("teacher@fit.hcmus.edu.vn")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(CreateUserUsecase.DEFAULT_PASSWORD)).thenReturn(encodedPassword);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(10L);
            return user;
        });

        CreateUserResponse response = createUserUsecase.execute(
                new CreateUserRequest(Role.TEACHER, " Teacher@FIT.HCMUS.EDU.VN ")
        );

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        verify(passwordEncoder).encode("Vlchinsu1234*");

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("teacher@fit.hcmus.edu.vn");
        assertThat(savedUser.getPassword()).isEqualTo(encodedPassword);
        assertThat(savedUser.getFullName()).isEqualTo("teacher");
        assertThat(savedUser.getRole()).isEqualTo(Role.TEACHER);
        assertThat(savedUser.getIsActive()).isTrue();
        assertThat(savedUser.getCreatedAt()).isNotNull();
        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.email()).isEqualTo("teacher@fit.hcmus.edu.vn");
    }

    @Test
    void execute_should_reject_email_outside_fit_domain() {
        CreateUserRequest request = new CreateUserRequest(Role.STUDENT, "student@gmail.com");

        assertThatThrownBy(() -> createUserUsecase.execute(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Email must belong to @fit.hcmus.edu.vn");

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void execute_should_reject_duplicate_email() {
        String email = "admin@fit.hcmus.edu.vn";
        User existingUser = User.builder().id(1L).email(email).build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> createUserUsecase.execute(new CreateUserRequest(Role.ADMIN, email)))
                .isInstanceOf(ConflictException.class);

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }
}
