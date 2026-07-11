package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.GetStudentDashboardUsecase;
import graduation_project_be.application.usecases.request.GetStudentDashboardRequest;
import graduation_project_be.application.usecases.response.GetStudentDashboardResponse;
import graduation_project_be.application.port.services.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher/students")
@RequiredArgsConstructor
public class TeacherStudentController {

    private final GetStudentDashboardUsecase getStudentDashboardUsecase;
    private final CurrentUserService currentUserService;

    @GetMapping("/{studentId}/dashboard")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> getDashboard(@PathVariable Long studentId) {
        Long teacherId = currentUserService.getCurrentUserId();
        
        GetStudentDashboardRequest request = GetStudentDashboardRequest.builder()
                .teacherId(teacherId)
                .studentId(studentId)
                .build();
                
        GetStudentDashboardResponse response = getStudentDashboardUsecase.execute(request);
        
        return ResponseEntity.ok(
                ResponseDto.of(
                        response,
                        "OK",
                        "Get student dashboard successfully"));
    }
}
