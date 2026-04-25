package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.RefreshTokenRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.application.port.services.RefreshTokenHasher;
import graduation_project_be.application.usecases.request.RefreshTokenRequest;
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
    private final UserRepository userRepository;

    public RefreshTokenResponse execute(RefreshTokenRequest refreshTokenRequest) {
        String refreshToken = refreshTokenRequest.refreshToken();
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

        User user = userRepository.findById(Long.valueOf(userId))
            .orElseThrow(() -> new UnauthorizedException("User no longer exists"));

        String newAccess = jwtService.generateToken(user);

        refreshTokenRepository.save(userId, tokenId, storedHash, jwtService.getJwtRefreshTokenValiditySeconds());

        LocalDateTime accessTokenExpiresAt = TimeUtils.now().plusSeconds(jwtService.getJwtTokenValiditySeconds());
        LocalDateTime refreshTokenExpiresAt = TimeUtils.now().plusSeconds(jwtService.getJwtRefreshTokenValiditySeconds());

        Token token = Token.builder()
            .accessToken(newAccess)
            .refreshToken(refreshToken)
            .accessTokenExpiresAt(accessTokenExpiresAt)
            .refreshTokenExpiresAt(refreshTokenExpiresAt)
            .build();

        return RefreshTokenResponse.fromModel(token);
    }
}
