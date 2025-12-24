package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.CreateClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetClassesRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.CreateClassResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.GetClassesResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.PaginationResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.CreateClassUsecase;
import graduation_project_be.application.usecases.GetClassesUsecase;
import graduation_project_be.application.usecases.request.CreateClassRequest;
import graduation_project_be.application.usecases.request.GetClassesRequest;
import graduation_project_be.application.usecases.response.CreateClassResponse;
import graduation_project_be.application.usecases.response.GetClassesResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teachers")
@RequiredArgsConstructor
public class TeacherController {

        private final CreateClassUsecase createClassUsecase;
        private final GetClassesUsecase getClassesUsecase;

        @PostMapping("/classes")
        public ResponseEntity<ResponseDto> createClass(
                        @RequestBody CreateClassRequestDto requestDto) {
                CreateClassRequest usecaseRequest = requestDto.toRequest();
                CreateClassResponse usecaseResponse = createClassUsecase.execute(usecaseRequest);
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(CreateClassResponseDto.fromResponse(usecaseResponse), "OK",
                                                "Class created successfully"));
        }

        @GetMapping("/classes")
        public ResponseEntity<PaginationResponseDto<GetClassesResponseDto>> getClasses(
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "10") int size,
                        @RequestParam(defaultValue = "CREATED_AT") String sortBy,
                        @RequestParam(defaultValue = "DESC") String sortOrder) {
                GetClassesRequestDto requestDto = GetClassesRequestDto.builder()
                                .page(page)
                                .size(size)
                                .sortBy(sortBy)
                                .sortOrder(sortOrder)
                                .build();
                GetClassesRequest usecaseRequest = requestDto.toRequest();
                PaginationResponse<GetClassesResponse> usecaseResponse = getClassesUsecase.execute(usecaseRequest);

                return ResponseEntity.ok()
                                .body(PaginationResponseDto.fromResponse(usecaseResponse,
                                                GetClassesResponseDto::fromResponse, "OK",
                                                "Classes retrieved successfully"));
        }
}
