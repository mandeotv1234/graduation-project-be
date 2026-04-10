package graduation_project_be.adapter.web.api.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import graduation_project_be.adapter.web.api.dtos.request.CreateSpecificationRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.CreateSpecificationV2RequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GenerateSchemaFromDdlRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GenerateSpecificationSchemaRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.UpdateSpecificationRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.ExamSpecificationResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.SpecificationResponseDto;
import graduation_project_be.application.usecases.CreateSpecificationUsecase;
import graduation_project_be.application.usecases.DeleteSpecificationUsecase;
import graduation_project_be.application.usecases.GenerateSpecificationSchemaUsecase;
import graduation_project_be.application.usecases.GenerateSchemaFromDdlUsecase;
import graduation_project_be.application.usecases.GetSpecificationByIdUsecase;
import graduation_project_be.application.usecases.GetSpecificationsUsecase;
import graduation_project_be.application.usecases.UpdateSpecificationUsecase;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.application.usecases.response.SpecificationResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/specifications")
@RequiredArgsConstructor
@Validated
public class SpecificationController {

        private final CreateSpecificationUsecase createSpecificationUsecase;
        private final UpdateSpecificationUsecase updateSpecificationUsecase;
        private final GetSpecificationsUsecase getSpecificationsUsecase;
        private final GetSpecificationByIdUsecase getSpecificationByIdUsecase;
        private final DeleteSpecificationUsecase deleteSpecificationUsecase;
        private final GenerateSpecificationSchemaUsecase generateSpecificationSchemaUsecase;
        private final GenerateSchemaFromDdlUsecase generateSchemaFromDdlUsecase;

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

        @PostMapping("/v2")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> createSpecificationV2(
                        @RequestBody @Valid CreateSpecificationV2RequestDto requestDto) {
                SpecificationResponse response = createSpecificationUsecase.execute(requestDto.toRequest());
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(
                                                SpecificationResponseDto.fromResponse(response),
                                                "CREATED",
                                                "Specification created successfully (v2)"));
        }

        @PostMapping("/ai-schema")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> generateSchemaByAi(
                        @RequestBody @Valid GenerateSpecificationSchemaRequestDto requestDto) {
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                generateSpecificationSchemaUsecase.execute(
                                                                requestDto.description(),
                                                                requestDto.currentSchemaJson()),
                                                "SUCCESS",
                                                "Schema generated successfully"));
        }

        @PostMapping("/schema-from-ddl")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> generateSchemaFromDdl(
                        @RequestBody @Valid GenerateSchemaFromDdlRequestDto requestDto) {
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                generateSchemaFromDdlUsecase.execute(requestDto.ddlScript()),
                                                "SUCCESS",
                                                "Schema generated from DDL successfully"));
        }

        @PutMapping("/{specificationId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> updateSpecification(
                        @PathVariable("specificationId") @Positive Long specificationId,
                        @RequestBody @Valid UpdateSpecificationRequestDto requestDto) {
                ExamSpecificationResponse response = updateSpecificationUsecase.execute(specificationId,
                                requestDto.toRequest());
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                ExamSpecificationResponseDto.fromResponse(response),
                                                "SUCCESS",
                                                "Specification updated successfully"));
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
                        @PathVariable("specificationId") @Positive Long specificationId) {
                ExamSpecificationResponse response = getSpecificationByIdUsecase.execute(specificationId);
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                ExamSpecificationResponseDto.fromResponse(response),
                                                "OK",
                                                "Exam specification retrieved successfully"));
        }

        @DeleteMapping("/{specificationId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> deleteSpecification(
                        @PathVariable("specificationId") @Positive Long specificationId) {
                deleteSpecificationUsecase.execute(specificationId);
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                null,
                                                "SUCCESS",
                                                "Specification deleted successfully"));
        }
}
