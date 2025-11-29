package graduation_project_be.auth.application.port;

import graduation_project_be.user.domain.User;

import java.util.List;

public interface JwtService {
    String generateToken(User user);
    boolean validateToken(String token);
    String extractEmail(String token);
    String extractRole(String token);
    String extractStatus(String token);
    List<String> extractPermissions(String token);
}
