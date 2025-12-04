package graduation_project_be.application.port.services;

public interface RefreshTokenHasher {
    String hash(String token);
    boolean verify(String token, String hash);
}

