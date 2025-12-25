package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.GetStudentExamUsecase;
import graduation_project_be.application.usecases.response.GetStudentExamResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
@Validated
public class StudentController {

    private final GetStudentExamUsecase getStudentExamUsecase;

    @GetMapping("/exams/{examId}")
    public ResponseEntity<ResponseDto> getExam(@PathVariable @Positive Long examId) {
        GetStudentExamResponse response = getStudentExamUsecase.execute(examId);
        return ResponseEntity.ok(ResponseDto.of(response, "Exam retrieved successfully"));
    }
}
