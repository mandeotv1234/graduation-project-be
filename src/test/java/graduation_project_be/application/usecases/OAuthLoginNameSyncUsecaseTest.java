package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.GoogleAuthService;
import graduation_project_be.application.port.services.MicrosoftAuthService;
import graduation_project_be.application.usecases.request.GoogleLoginRequest;
import graduation_project_be.application.usecases.request.MicrosoftLoginRequest;
import graduation_project_be.application.usecases.response.LoginResponse;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthLoginNameSyncUsecaseTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private GoogleAuthService googleAuthService;

    @Mock
    private MicrosoftAuthService microsoftAuthService;

    @Mock
    private TokenIssuer tokenIssuer;

    @Test
    void googleLogin_should_replace_mock_name_without_changing_role_or_password() {
        User existingUser = User.builder()
                .id(1L)
                .email("admin@fit.hcmus.edu.vn")
                .password("$2a$10$existing-hash")
                .fullName("admin")
                .googleSubject("google-subject")
                .role(Role.ADMIN)
                .isActive(true)
                .build();
        GoogleAuthService.GoogleUserInfo googleUserInfo = new GoogleAuthService.GoogleUserInfo(
                existingUser.getEmail(),
                "google-subject",
                "  Nguyễn Văn Admin  "
        );
        LoginResponse expectedResponse = LoginResponse.builder().accessToken("access-token").build();

        when(googleAuthService.verifyGoogleToken("code", "http://localhost:3000"))
                .thenReturn(googleUserInfo);
        when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
        when(userRepository.save(existingUser)).thenReturn(existingUser);
        when(tokenIssuer.issueToken(existingUser)).thenReturn(expectedResponse);

        GoogleLoginUsecase usecase = new GoogleLoginUsecase(userRepository, googleAuthService, tokenIssuer);
        LoginResponse response = usecase.execute(new GoogleLoginRequest(
                "code",
                "http://localhost:3000",
                true
        ));

        assertThat(existingUser.getFullName()).isEqualTo("Nguyễn Văn Admin");
        assertThat(existingUser.getRole()).isEqualTo(Role.ADMIN);
        assertThat(existingUser.getPassword()).isEqualTo("$2a$10$existing-hash");
        assertThat(response).isSameAs(expectedResponse);
        verify(userRepository).save(existingUser);
    }

    @Test
    void microsoftLogin_should_replace_mock_name_without_changing_role_or_password() {
        User existingUser = User.builder()
                .id(2L)
                .email("22120001@student.hcmus.edu.vn")
                .password("$2a$10$existing-student-hash")
                .fullName("Tên mock")
                .role(Role.STUDENT)
                .isActive(true)
                .build();
        MicrosoftAuthService.MicrosoftUserInfo microsoftUserInfo =
                new MicrosoftAuthService.MicrosoftUserInfo(
                        existingUser.getEmail(),
                        "microsoft-subject",
                        "  Nguyễn Văn Sinh Viên  "
                );
        LoginResponse expectedResponse = LoginResponse.builder().accessToken("access-token").build();

        when(microsoftAuthService.verifyMicrosoftToken("id-token")).thenReturn(microsoftUserInfo);
        when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
        when(userRepository.save(existingUser)).thenReturn(existingUser);
        when(tokenIssuer.issueToken(existingUser)).thenReturn(expectedResponse);

        MicrosoftLoginUsecase usecase = new MicrosoftLoginUsecase(
                userRepository,
                microsoftAuthService,
                tokenIssuer
        );
        LoginResponse response = usecase.execute(new MicrosoftLoginRequest("id-token"));

        assertThat(existingUser.getFullName()).isEqualTo("Nguyễn Văn Sinh Viên");
        assertThat(existingUser.getRole()).isEqualTo(Role.STUDENT);
        assertThat(existingUser.getPassword()).isEqualTo("$2a$10$existing-student-hash");
        assertThat(response).isSameAs(expectedResponse);
        verify(userRepository).save(existingUser);
    }

    @Test
    void microsoftLogin_should_keep_existing_name_when_provider_name_is_blank() {
        User existingUser = User.builder()
                .id(3L)
                .email("22120002@student.hcmus.edu.vn")
                .fullName("Tên hiện tại")
                .role(Role.STUDENT)
                .isActive(true)
                .build();
        MicrosoftAuthService.MicrosoftUserInfo microsoftUserInfo =
                new MicrosoftAuthService.MicrosoftUserInfo(existingUser.getEmail(), "subject", "   ");

        when(microsoftAuthService.verifyMicrosoftToken("id-token")).thenReturn(microsoftUserInfo);
        when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
        when(tokenIssuer.issueToken(existingUser)).thenReturn(LoginResponse.builder().build());

        MicrosoftLoginUsecase usecase = new MicrosoftLoginUsecase(
                userRepository,
                microsoftAuthService,
                tokenIssuer
        );
        usecase.execute(new MicrosoftLoginRequest("id-token"));

        assertThat(existingUser.getFullName()).isEqualTo("Tên hiện tại");
        verify(userRepository, never()).save(any(User.class));
    }
}
