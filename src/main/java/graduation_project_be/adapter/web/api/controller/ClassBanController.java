package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.BanStudentRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.BannedStudentResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.PaginationResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.BanStudentUsecase;
import graduation_project_be.application.usecases.GetClassBansUsecase;
import graduation_project_be.application.usecases.UnbanStudentUsecase;
import graduation_project_be.application.usecases.response.BannedStudentResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
@Validated
public class ClassBanController {

    private final BanStudentUsecase banStudentUsecase;
    private final UnbanStudentUsecase unbanStudentUsecase;
    private final GetClassBansUsecase getClassBansUsecase;

    @GetMapping("/{classId}/bans")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<PaginationResponseDto<BannedStudentResponseDto>> getClassBans(
            @PathVariable("classId") Long classId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        PaginationResponse<BannedStudentResponse> response = getClassBansUsecase.execute(classId, page, size);
        return ResponseEntity.ok(PaginationResponseDto.fromResponse(
                response, BannedStudentResponseDto::fromResponse, "OK", "Banned students retrieved successfully"));
    }

    @PostMapping("/{classId}/bans")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> banStudent(
            @PathVariable("classId") Long classId,
            @RequestBody @Valid BanStudentRequestDto requestDto) {
        banStudentUsecase.execute(requestDto.toRequest(classId));
        return ResponseEntity.ok(ResponseDto.of(null, "OK", "Student banned successfully"));
    }

    @DeleteMapping("/{classId}/bans/{studentId}")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> unbanStudent(
            @PathVariable("classId") Long classId,
            @PathVariable("studentId") Long studentId) {
        unbanStudentUsecase.execute(classId, studentId);
        return ResponseEntity.ok(ResponseDto.of(null, "OK", "Student unbanned successfully"));
    }
}
