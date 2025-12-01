package graduation_project_be.auth.adapter.web;


import graduation_project_be.auth.adapter.web.dtos.LoginRequestDto;
import graduation_project_be.auth.adapter.web.dtos.LoginResponseDto;
import graduation_project_be.shared.adapter.web.dtos.ResponseDto;
import graduation_project_be.auth.application.usecase.LoginUsecase;
import graduation_project_be.auth.application.usecase.dtos.request.LoginRequest;
import graduation_project_be.auth.application.usecase.dtos.response.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "1. Authentication", description = "APIs for user authentication")
public class AuthController {
    private final LoginUsecase loginUsecase;

    @Operation(summary = "User Login", description = "Authenticate user and return access token.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Login successful",
                    content = @Content(schema = @Schema(implementation = LoginResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid credentials"),
            @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input")
    })
    @PostMapping("/login")
    public ResponseEntity<ResponseDto> login(@RequestBody @Valid LoginRequestDto loginRequestDto) {

        LoginRequest loginRequest = new LoginRequest(loginRequestDto.email(), loginRequestDto.password());
        LoginResponse loginResponse = loginUsecase.execute(loginRequest);
        LoginResponseDto loginResponseDto = LoginResponseDto.fromResponse(loginResponse);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(new ResponseDto(loginResponseDto));
    }
}
