package graduation_project_be.application.port.services;

import graduation_project_be.domain.models.User;

import java.util.List;

public interface JwtService {
    String generateToken(User user);
    String generateRefreshToken(User user);
    boolean validateToken(String token);
    String extractEmail(String token);
    String extractRole(String token);
    String extractStatus(String token);
    List<String> extractPermissions(String token);
}
