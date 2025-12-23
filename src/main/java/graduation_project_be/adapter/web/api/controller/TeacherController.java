package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.CreateClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetClassesRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.CreateClassResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.GetClassesResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.PaginationMetaDto;
import graduation_project_be.adapter.web.api.dtos.response.PaginationResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.CreateClassUsecase;
import graduation_project_be.application.usecases.GetClassesUsecase;
import graduation_project_be.application.usecases.request.CreateClassRequest;
import graduation_project_be.application.usecases.request.GetClassesRequest;
import graduation_project_be.application.usecases.response.CreateClassResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
                        @RequestParam(defaultValue = "TIME") String sortBy,
                        @RequestParam(defaultValue = "DESC") String sortOrder) {
                GetClassesRequestDto requestDto = new GetClassesRequestDto();
                requestDto.setPage(page);
                requestDto.setSize(size);
                requestDto.setSortBy(sortBy);
                requestDto.setSortOrder(sortOrder);

                GetClassesRequest usecaseRequest = requestDto.toRequest();
                var usecaseResponse = getClassesUsecase.execute(usecaseRequest);

                List<GetClassesResponseDto> responseDtos = usecaseResponse.data().stream()
                                .map(GetClassesResponseDto::fromResponse)
                                .toList();

                // Tạo pagination meta từ usecase response
                PaginationMetaDto paginationMeta = new PaginationMetaDto(
                                usecaseResponse.pagination().getPage(),
                                usecaseResponse.pagination().getSize(),
                                usecaseResponse.pagination().getTotal());

                return ResponseEntity.ok()
                                .body(PaginationResponseDto.of(responseDtos, paginationMeta, "OK",
                                                "Classes retrieved successfully"));
        }
}
