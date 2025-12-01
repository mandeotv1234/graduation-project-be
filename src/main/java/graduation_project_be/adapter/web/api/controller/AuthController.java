package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.LoginRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.LoginResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.LoginUsecase;
import graduation_project_be.application.usecases.request.LoginRequest;
import graduation_project_be.application.usecases.response.LoginResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final LoginUsecase loginUsecase;

    @PostMapping("/login")
    public ResponseEntity<ResponseDto> login(@RequestBody @Valid LoginRequestDto loginRequestDto) {

        LoginRequest loginRequest = loginRequestDto.toRequest();
        LoginResponse loginResponse = loginUsecase.execute(loginRequest);
        LoginResponseDto loginResponseDto = LoginResponseDto.fromResponse(loginResponse);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ResponseDto.of(loginResponseDto, "Login successful"));
    }
}
