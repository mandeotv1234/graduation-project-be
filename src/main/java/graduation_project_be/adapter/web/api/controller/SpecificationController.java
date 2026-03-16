package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.CreateSpecificationRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.ExamSpecificationResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.SpecificationResponseDto;
import graduation_project_be.application.usecases.CreateSpecificationUsecase;
import graduation_project_be.application.usecases.GetSpecificationByIdUsecase;
import graduation_project_be.application.usecases.GetSpecificationsUsecase;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.application.usecases.response.SpecificationResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/specifications")
@RequiredArgsConstructor
@Validated
public class SpecificationController {

    private final CreateSpecificationUsecase createSpecificationUsecase;
    private final GetSpecificationsUsecase getSpecificationsUsecase;
    private final GetSpecificationByIdUsecase getSpecificationByIdUsecase;

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> createSpecification(
            @RequestBody @Valid CreateSpecificationRequestDto requestDto) {
        SpecificationResponse response = createSpecificationUsecase.execute(requestDto.toRequest());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDto.of(
                        SpecificationResponseDto.fromResponse(response),
                        "CREATED",
                        "Specification created successfully"));
    }

    @GetMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> getSpecifications() {
        List<SpecificationResponse> responses = getSpecificationsUsecase.execute();
        List<SpecificationResponseDto> dtos = responses.stream()
                .map(SpecificationResponseDto::fromResponse)
                .toList();
        return ResponseEntity.ok(
                ResponseDto.of(dtos, "OK", "Specifications retrieved successfully"));
    }

    @GetMapping("/{specificationId}")
    @PreAuthorize("hasAnyRole('TEACHER', 'STUDENT')")
    public ResponseEntity<ResponseDto> getSpecificationById(
            @PathVariable @Positive Long specificationId) {
        ExamSpecificationResponse response = getSpecificationByIdUsecase.execute(specificationId);
        return ResponseEntity.ok(
                ResponseDto.of(
                        ExamSpecificationResponseDto.fromResponse(response),
                        "OK",
                        "Exam specification retrieved successfully"));
    }
}
