package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.CreateClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetClassesRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.CreateClassResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.GetClassDetailResponseDto;
import graduation_project_be.adapter.web.api.dtos.request.GetClassDetailRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.GetClassesResponseDto;
import graduation_project_be.adapter.web.api.dtos.request.GetStudentsInClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.GetStudentsInClassResponseDto;
import graduation_project_be.adapter.web.api.dtos.request.GetStudentsInClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.GetStudentsInClassResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.PaginationResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.CreateClassUsecase;
import graduation_project_be.application.usecases.GetClassesUsecase;
import graduation_project_be.application.usecases.GetStudentsInClassUsecase;
import graduation_project_be.application.usecases.GetStudentsInClassUsecase;
import graduation_project_be.application.usecases.request.CreateClassRequest;
import graduation_project_be.application.usecases.request.GetClassDetailRequest;
import graduation_project_be.application.usecases.request.GetClassesRequest;
import graduation_project_be.application.usecases.request.GetStudentsInClassRequest;
import graduation_project_be.application.usecases.request.GetStudentsInClassRequest;
import graduation_project_be.application.usecases.response.CreateClassResponse;
import graduation_project_be.application.usecases.response.GetClassDetailResponse;
import graduation_project_be.application.usecases.response.GetClassesResponse;
import graduation_project_be.application.usecases.response.GetStudentsInClassResponse;
import graduation_project_be.application.usecases.response.GetStudentsInClassResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.application.usecases.GetClassDetailUsecase;
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
        private final GetStudentsInClassUsecase getStudentsInClassUsecase;
        private final GetClassDetailUsecase getClassDetailUsecase;

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
                GetClassesRequest usecaseRequest = requestDto.toRequest();
                PaginationResponse<GetClassesResponse> usecaseResponse = getClassesUsecase.execute(usecaseRequest);

                return ResponseEntity.ok()
                                .body(PaginationResponseDto.fromResponse(usecaseResponse,
                                                GetClassesResponseDto::fromResponse, "OK",
                                                "Classes retrieved successfully"));
        }

        @GetMapping("classes/{classId}/students")
        public ResponseEntity<PaginationResponseDto<GetStudentsInClassResponseDto>> getStudentsInClass(
                @PathVariable Long classId,
                @RequestParam(defaultValue = "1") int page,
                @RequestParam(defaultValue = "10") int size,
                @RequestParam(defaultValue = "FULL_NAME") String sortBy,
                @RequestParam(defaultValue = "ASC") String sortOrder
        ) {
            GetStudentsInClassRequestDto requestDto = GetStudentsInClassRequestDto.builder()
                    .classId(classId)
                    .page(page)
                    .size(size)
                    .sortBy(sortBy)
                    .sortOrder(sortOrder)
                    .build();

            GetStudentsInClassRequest usecaseRequest = requestDto.toRequest();


            PaginationResponse<GetStudentsInClassResponse> usecaseResponse = getStudentsInClassUsecase.execute(usecaseRequest);

            return ResponseEntity.ok()
                    .body(PaginationResponseDto.fromResponse(usecaseResponse,
                            GetStudentsInClassResponseDto::fromResponse, "OK",
                            "Students retrieved successfully"));
        }

        @GetMapping("/classes/{classId}")
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
}
