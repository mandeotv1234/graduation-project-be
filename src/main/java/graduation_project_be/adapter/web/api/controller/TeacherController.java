package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.CreateClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.CreateClassResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.CreateClassUsecase;
import graduation_project_be.application.usecases.request.CreateClassRequest;
import graduation_project_be.application.usecases.response.CreateClassResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teachers")
@RequiredArgsConstructor
public class TeacherController {

    private final CreateClassUsecase createClassUsecase;

    @PostMapping("/classes")
    public ResponseEntity<ResponseDto> createClass(
            @RequestBody CreateClassRequestDto requestDto
    ) {
        CreateClassRequest usecaseRequest = requestDto.toRequest();
        CreateClassResponse usecaseResponse = createClassUsecase.execute(usecaseRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDto.of(CreateClassResponseDto.fromResponse(usecaseResponse), "OK", "Class created successfully"));
    }
}
