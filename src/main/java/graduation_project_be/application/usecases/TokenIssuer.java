package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.port.repositories.RefreshTokenRepository;
import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.application.port.services.RefreshTokenHasher;
import graduation_project_be.application.usecases.response.LoginResponse;
import graduation_project_be.domain.models.Token;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;

    public LoginResponse issueToken(User user) {
        String accessToken = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        String userId = String.valueOf(user.getId());
        refreshTokenRepository.deleteAllByUserId(userId);

        String tokenId = jwtService.extractTokenId(refreshToken);
        String hashed = refreshTokenHasher.hash(refreshToken);
        refreshTokenRepository.save(userId, tokenId, hashed, jwtService.getJwtRefreshTokenValiditySeconds());

        LocalDateTime accessTokenExpiresAt = TimeUtils.now().plusSeconds(jwtService.getJwtTokenValiditySeconds());
        LocalDateTime refreshTokenExpiresAt = TimeUtils.now().plusSeconds(jwtService.getJwtRefreshTokenValiditySeconds());

        Token token = Token.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .accessTokenExpiresAt(accessTokenExpiresAt)
                .refreshTokenExpiresAt(refreshTokenExpiresAt)
                .build();

        return LoginResponse.fromModel(token);
    }
}
