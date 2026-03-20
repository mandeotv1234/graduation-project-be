package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.LoginRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.RefreshTokenRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.LogoutRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GoogleLoginRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.MicrosoftLoginRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.LoginResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.MessageResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.RefreshTokenResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.LoginUsecase;
import graduation_project_be.application.usecases.RefreshUsecase;
import graduation_project_be.application.usecases.LogoutUsecase;
import graduation_project_be.application.usecases.GoogleLoginUsecase;
import graduation_project_be.application.usecases.MicrosoftLoginUsecase;
import graduation_project_be.application.usecases.request.LoginRequest;
import graduation_project_be.application.usecases.request.RefreshTokenRequest;
import graduation_project_be.application.usecases.request.LogoutRequest;
import graduation_project_be.application.usecases.request.GoogleLoginRequest;
import graduation_project_be.application.usecases.request.MicrosoftLoginRequest;
import graduation_project_be.application.usecases.response.LoginResponse;
import graduation_project_be.application.usecases.response.RefreshTokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LoginUsecase loginUsecase;
    private final RefreshUsecase refreshUsecase;
    private final LogoutUsecase logoutUsecase;
    private final GoogleLoginUsecase googleLoginUsecase;
    private final MicrosoftLoginUsecase microsoftLoginUsecase;

    @PostMapping("/login")
    public ResponseEntity<ResponseDto> login(@RequestBody @Valid LoginRequestDto loginRequestDto) {

        LoginRequest loginRequest = loginRequestDto.toRequest();
        LoginResponse loginResponse = loginUsecase.execute(loginRequest);

        return ResponseEntity
                .ok()
                .body(ResponseDto.of(LoginResponseDto.fromResponse(loginResponse), "OK", "Login successful"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ResponseDto> refresh(@RequestBody @Valid RefreshTokenRequestDto refreshTokenRequestDto) {
        RefreshTokenRequest refreshTokenRequest = refreshTokenRequestDto.toRequest();

        RefreshTokenResponse refreshTokenResponse = refreshUsecase.execute(refreshTokenRequest);

        return ResponseEntity.ok()
                .body(ResponseDto.of(RefreshTokenResponseDto.fromResponse(refreshTokenResponse), "OK",
                        "Token refreshed"));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponseDto> logout(@RequestBody @Valid LogoutRequestDto logoutRequestDto) {
        LogoutRequest logoutRequest = logoutRequestDto.toRequest();
        logoutUsecase.execute(logoutRequest);

        return ResponseEntity.ok()
                .body(MessageResponseDto.of("Logout successful"));
    }

    @PostMapping("/google")
    public ResponseEntity<ResponseDto> googleLogin(@RequestBody @Valid GoogleLoginRequestDto googleLoginRequestDto) {
        GoogleLoginRequest googleLoginRequest = googleLoginRequestDto.toRequest();
        LoginResponse loginResponse = googleLoginUsecase.execute(googleLoginRequest);

        return ResponseEntity
                .ok()
                .body(ResponseDto.of(LoginResponseDto.fromResponse(loginResponse), "OK", "Google login successful"));
    }

    @PostMapping("/microsoft")
    public ResponseEntity<ResponseDto> microsoftLogin(@RequestBody @Valid MicrosoftLoginRequestDto microsoftLoginRequestDto) {
        MicrosoftLoginRequest microsoftLoginRequest = microsoftLoginRequestDto.toRequest();
        LoginResponse loginResponse = microsoftLoginUsecase.execute(microsoftLoginRequest);

        return ResponseEntity
                .ok()
                .body(ResponseDto.of(LoginResponseDto.fromResponse(loginResponse), "OK", "Microsoft login successful"));
    }
}
