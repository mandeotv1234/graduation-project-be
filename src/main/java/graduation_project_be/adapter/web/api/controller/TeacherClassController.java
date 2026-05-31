package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.AddTeacherToClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.ClassTeacherResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.AddTeacherToClassUsecase;
import graduation_project_be.application.usecases.GetClassTeachersUsecase;
import graduation_project_be.application.usecases.RemoveTeacherFromClassUsecase;
import graduation_project_be.application.usecases.request.AddTeacherToClassRequest;
import graduation_project_be.application.usecases.response.GetClassTeachersResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
public class TeacherClassController {

        private final AddTeacherToClassUsecase addTeacherToClassUsecase;
        private final GetClassTeachersUsecase getClassTeachersUsecase;
        private final RemoveTeacherFromClassUsecase removeTeacherFromClassUsecase;

        @GetMapping("/{classId}/teachers")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getClassTeachers(
                        @PathVariable("classId") Long classId) {
                List<GetClassTeachersResponse> responses = getClassTeachersUsecase.execute(classId);
                List<ClassTeacherResponseDto> dtos = responses.stream()
                                .map(ClassTeacherResponseDto::fromResponse)
                                .toList();

                return ResponseEntity.ok(
                                ResponseDto.of(dtos, "OK", "Class teachers retrieved successfully"));
        }

        @PostMapping("/{classId}/teachers")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> addTeacherToClass(
                        @PathVariable("classId") Long classId,
                        @RequestBody @Valid AddTeacherToClassRequestDto requestDto) {
                AddTeacherToClassRequest request = requestDto.toRequest(classId);
                addTeacherToClassUsecase.execute(request);

                return ResponseEntity.ok(
                                ResponseDto.of(null, "OK", "Thêm giảng viên vào lớp học thành công!"));
        }

        @DeleteMapping("/{classId}/teachers/{teacherId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> removeTeacherFromClass(
                        @PathVariable("classId") Long classId,
                        @PathVariable("teacherId") Long teacherId) {
                removeTeacherFromClassUsecase.execute(classId, teacherId);

                return ResponseEntity.ok(
                                ResponseDto.of(null, "OK", "Xóa giảng viên khỏi lớp học thành công!"));
        }
}
