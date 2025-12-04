package graduation_project_be.application.port.repositories;


public interface RefreshTokenRepository {
    void save(String userId, String tokenId, String hashedToken, long ttlSeconds);
    String find(String userId, String tokenId);
    void delete(String userId, String tokenId);
    void deleteAllByUserId(String userId);
}

