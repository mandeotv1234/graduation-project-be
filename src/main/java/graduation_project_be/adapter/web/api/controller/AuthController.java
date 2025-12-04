package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.LoginRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.LoginResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.MessageResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.RefreshTokenResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.application.usecases.LoginUsecase;
import graduation_project_be.application.usecases.RefreshUsecase;
import graduation_project_be.application.usecases.LogoutUsecase;
import graduation_project_be.application.usecases.request.LoginRequest;
import graduation_project_be.application.usecases.response.LoginResponse;
import graduation_project_be.application.usecases.response.RefreshTokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LoginUsecase loginUsecase;
    private final RefreshUsecase refreshUsecase;
    private final LogoutUsecase logoutUsecase;
    private final JwtService jwtService;

    private static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth/refresh";

    private ResponseCookie createRefreshCookie(String token) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path(COOKIE_PATH)
                .maxAge(token == null ? 0 : jwtService.getJwtRefreshTokenValiditySeconds())
                .build();
    }

    @PostMapping("/login")
    public ResponseEntity<ResponseDto> login(@RequestBody @Valid LoginRequestDto loginRequestDto) {

        LoginRequest loginRequest = loginRequestDto.toRequest();
        LoginResponse loginResponse = loginUsecase.execute(loginRequest);

        ResponseCookie cookie = createRefreshCookie(loginResponse.refreshToken());

        return ResponseEntity
                .ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ResponseDto.of(LoginResponseDto.fromResponse(loginResponse), "OK", "Login successful"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ResponseDto> refresh(
            @CookieValue(name = COOKIE_NAME, required = false) String refreshToken
    ) {

        RefreshTokenResponse refreshTokenResponse = refreshUsecase.execute(refreshToken);

        ResponseCookie cookie = createRefreshCookie(refreshTokenResponse.refreshToken());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ResponseDto.of(RefreshTokenResponseDto.fromResponse(refreshTokenResponse), "OK", "Token refreshed"));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponseDto> logout(
            @CookieValue(name = COOKIE_NAME, required = false) String refreshToken
    ) {
        if (refreshToken != null) {
            logoutUsecase.execute(refreshToken);
        }

        ResponseCookie deleteCookie = createRefreshCookie(null);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, deleteCookie.toString())
                .body(MessageResponseDto.of("Logout successful"));
    }
}
