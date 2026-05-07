package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.UpdateUserRoleRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.GetUsersResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.PaginationResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.UpdateUserRoleResponseDto;
import graduation_project_be.application.usecases.GetUsersUsecase;
import graduation_project_be.application.usecases.UpdateUserRoleUsecase;
import graduation_project_be.application.usecases.request.GetUsersRequest;
import graduation_project_be.application.usecases.response.GetUsersResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.application.usecases.response.UpdateUserRoleResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Validated
public class AdminUserController {

    private final GetUsersUsecase getUsersUsecase;
    private final UpdateUserRoleUsecase updateUserRoleUsecase;

    @GetMapping
    public ResponseEntity<PaginationResponseDto<GetUsersResponseDto>> getUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        PaginationResponse<GetUsersResponse> response = getUsersUsecase.execute(new GetUsersRequest(page, size));

        return ResponseEntity.ok(
                PaginationResponseDto.fromResponse(response, GetUsersResponseDto::fromResponse, "200", "OK"));
    }

    @PatchMapping("/{userId}/role")
    public ResponseEntity<ResponseDto> updateUserRole(
            @PathVariable @Positive Long userId,
            @RequestBody @Valid UpdateUserRoleRequestDto requestDto) {

        UpdateUserRoleResponse response = updateUserRoleUsecase.execute(requestDto.toRequest(userId));

        return ResponseEntity.ok(
                ResponseDto.of(UpdateUserRoleResponseDto.fromResponse(response), "User role updated successfully"));
    }
}
