package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.RefreshTokenRepository;
import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.application.port.services.RefreshTokenHasher;
import graduation_project_be.application.usecases.response.RefreshTokenResponse;
import graduation_project_be.domain.models.Token;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

@RequiredArgsConstructor
public class RefreshUsecase {
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final RefreshTokenHasher refreshTokenHasher;

    public RefreshTokenResponse execute(String refreshToken) {
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

        String email = jwtService.extractEmail(refreshToken);
        User user = User.builder().id(Long.valueOf(userId)).email(email).build();

        String newAccess = jwtService.generateToken(user);
        String newRefresh = jwtService.generateRefreshToken(user);
        String newTokenId = jwtService.extractTokenId(newRefresh);
        String hashed = refreshTokenHasher.hash(newRefresh);
        refreshTokenRepository.save(userId, newTokenId, hashed, jwtService.getJwtRefreshTokenValiditySeconds());

        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(jwtService.getJwtTokenValiditySeconds());
        Token token = new Token(newAccess, newRefresh, expiresAt);
        return RefreshTokenResponse.fromModel(token);
    }
}
