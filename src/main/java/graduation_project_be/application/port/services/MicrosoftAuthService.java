package graduation_project_be.application.port.services;

public interface MicrosoftAuthService {
    
    MicrosoftUserInfo verifyMicrosoftToken(String accessToken);

    record MicrosoftUserInfo(String email, String subject, String name) {
    }
}
