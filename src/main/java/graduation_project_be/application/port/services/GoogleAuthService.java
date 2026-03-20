package graduation_project_be.application.port.services;

public interface GoogleAuthService {

    GoogleUserInfo verifyGoogleToken(String code, String redirectUri);

    record GoogleUserInfo(
            String email,
            String subject,
            String name) {
    }
}
