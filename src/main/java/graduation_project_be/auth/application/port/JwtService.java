package graduation_project_be.auth.application.port;

import java.util.List;

import graduation_project_be.user.domain.models.User;

public interface JwtService {
    String generateToken(User user);
    String generateRefreshToken(User user);
    boolean validateToken(String token);
    String extractEmail(String token);
    String extractRole(String token);
    String extractStatus(String token);
    List<String> extractPermissions(String token);
}
