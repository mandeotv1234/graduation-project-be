package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.GetCurrentUserResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.GetCurrentUserUsecase;
import graduation_project_be.application.usecases.request.GetCurrentUserRequest;
import graduation_project_be.application.usecases.response.GetCurrentUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

	private final GetCurrentUserUsecase getCurrentUserUsecase;

	@GetMapping("/me")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ResponseDto> getMe() {
		GetCurrentUserResponse response = getCurrentUserUsecase.execute(new GetCurrentUserRequest());
		return ResponseEntity.ok(
				ResponseDto.of(
						GetCurrentUserResponseDto.fromResponse(response),
						"OK",
						"Get current user successfully"));
	}

}
