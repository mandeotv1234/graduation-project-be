package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.CreateClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetClassDetailRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetClassesRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetStudentsInClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.*;
import graduation_project_be.application.usecases.CreateClassUsecase;
import graduation_project_be.application.usecases.GetClassDetailUsecase;
import graduation_project_be.application.usecases.GetClassesUsecase;
import graduation_project_be.application.usecases.GetExamsByClassUsecase;
import graduation_project_be.application.usecases.GetStudentsInClassUsecase;
import graduation_project_be.application.usecases.request.CreateClassRequest;
import graduation_project_be.application.usecases.request.GetStudentsInClassRequest;
import graduation_project_be.application.usecases.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
public class ClassController {

    private final CreateClassUsecase createClassUsecase;
    private final GetClassesUsecase getClassesUsecase;
    private final GetStudentsInClassUsecase getStudentsInClassUsecase;
    private final GetClassDetailUsecase getClassDetailUsecase;
    private final GetExamsByClassUsecase getExamsByClassUsecase;

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> createClass(
            @RequestBody @Valid CreateClassRequestDto requestDto) {
        CreateClassRequest usecaseRequest = requestDto.toRequest();
        CreateClassResponse usecaseResponse = createClassUsecase.execute(usecaseRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDto.of(CreateClassResponseDto.fromResponse(usecaseResponse), "CREATED",
                        "Class created successfully"));
    }

    @GetMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<PaginationResponseDto<GetClassesResponseDto>> getClasses(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "CREATED_AT") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortOrder) {
        GetClassesRequestDto requestDto = GetClassesRequestDto.builder()
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .build();

        PaginationResponse<GetClassesResponse> usecaseResponse = getClassesUsecase.execute(requestDto.toRequest());

        return ResponseEntity.ok()
                .body(PaginationResponseDto.fromResponse(usecaseResponse,
                        GetClassesResponseDto::fromResponse, "OK",
                        "Classes retrieved successfully"));
    }

    @GetMapping("/{classId}")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> getClassDetail(
            @PathVariable Long classId) {
        GetClassDetailRequestDto requestDto = GetClassDetailRequestDto.builder()
                .classId(classId)
                .build();

        GetClassDetailResponse usecaseResponse = getClassDetailUsecase.execute(requestDto.toRequest());
        return ResponseEntity.ok()
                .body(ResponseDto.of(GetClassDetailResponseDto.fromResponse(usecaseResponse), "OK",
                        "Class detail retrieved successfully"));
    }

    @GetMapping("/{classId}/students")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<PaginationResponseDto<GetStudentsInClassResponseDto>> getStudentsInClass(
            @PathVariable Long classId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "FULL_NAME") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortOrder) {
        GetStudentsInClassRequestDto requestDto = GetStudentsInClassRequestDto.builder()
                .classId(classId)
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .build();

        GetStudentsInClassRequest usecaseRequest = requestDto.toRequest();
        PaginationResponse<GetStudentsInClassResponse> usecaseResponse = getStudentsInClassUsecase
                .execute(usecaseRequest);

        return ResponseEntity.ok()
                .body(PaginationResponseDto.fromResponse(usecaseResponse,
                        GetStudentsInClassResponseDto::fromResponse, "OK",
                        "Students retrieved successfully"));
    }

    @GetMapping("/{classId}/exams")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> getExamsByClass(
            @PathVariable Long classId) {
        List<CreateExamResponse> responses = getExamsByClassUsecase.execute(classId);
        List<CreateExamResponseDto> dtos = responses.stream()
                .map(CreateExamResponseDto::fromResponse)
                .toList();
        return ResponseEntity.ok(
                ResponseDto.of(dtos, "OK", "Exams retrieved successfully"));
    }
}
