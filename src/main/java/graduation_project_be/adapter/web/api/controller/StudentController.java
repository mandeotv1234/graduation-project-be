package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.GetStudentExamUsecase;
import graduation_project_be.application.usecases.response.GetStudentExamResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import graduation_project_be.adapter.web.api.dtos.request.GetStudentExamDetailRequestDto;

import graduation_project_be.adapter.web.api.dtos.response.GetStudentExamResponseDto;

@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
@Validated
public class StudentController {

    private final GetStudentExamUsecase getStudentExamUsecase;

    @GetMapping("/exams/{examId}")
    public ResponseEntity<ResponseDto> getExam(@PathVariable @Positive Long examId) {
        GetStudentExamDetailRequestDto requestDto = GetStudentExamDetailRequestDto.builder()
                .examId(examId)
                .build();
        GetStudentExamResponse response = getStudentExamUsecase.execute(requestDto.toRequest());
        return ResponseEntity.ok(
                ResponseDto.of(GetStudentExamResponseDto.fromResponse(response), "OK", "Exam retrieved successfully"));
    }
}
