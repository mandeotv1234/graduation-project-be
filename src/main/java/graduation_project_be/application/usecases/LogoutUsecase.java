package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.RefreshTokenRepository;
import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.application.port.services.RefreshTokenHasher;
import graduation_project_be.application.usecases.request.LogoutRequest;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LogoutUsecase {
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final RefreshTokenHasher refreshTokenHasher;


    public void execute(LogoutRequest logoutRequest) {
        String refreshToken = logoutRequest.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException("Missing refresh token");
        }

        jwtService.validateToken(refreshToken);
        String userId = jwtService.extractUserId(refreshToken);
        String tokenId = jwtService.extractTokenId(refreshToken);

        if (userId == null || tokenId == null) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        String storedHash = refreshTokenRepository.find(userId, tokenId);
        if (storedHash == null) {
            refreshTokenRepository.deleteAllByUserId(userId);
            throw new UnauthorizedException("Refresh token reuse detected");
        }

        if (!refreshTokenHasher.verify(refreshToken, storedHash)) {
            refreshTokenRepository.deleteAllByUserId(userId);
            throw new UnauthorizedException("Refresh token reuse detected");
        }

        refreshTokenRepository.delete(userId, tokenId);
    }
}
