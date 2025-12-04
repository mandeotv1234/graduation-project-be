package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.RefreshTokenRepository;
import graduation_project_be.application.port.services.JwtService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LogoutUsecase {
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    public void execute(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        jwtService.validateToken(refreshToken);
        String userId = jwtService.extractUserId(refreshToken);
        String tokenId = jwtService.extractTokenId(refreshToken);
        if (userId == null || tokenId == null) {
            return;
        }

        refreshTokenRepository.delete(userId, tokenId);
    }
}
