package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.AddTeacherToClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.BanStudentRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.CreateClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetClassDetailRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetClassesRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetStudentsInClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.UpdateClassRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.*;
import graduation_project_be.application.usecases.AddTeacherToClassUsecase;
import graduation_project_be.application.usecases.BanStudentUsecase;
import graduation_project_be.application.usecases.GetClassTeachersUsecase;
import graduation_project_be.application.usecases.RemoveTeacherFromClassUsecase;
import graduation_project_be.application.usecases.UnbanStudentUsecase;
import graduation_project_be.application.usecases.GetClassBansUsecase;
import graduation_project_be.application.usecases.request.AddTeacherToClassRequest;
import graduation_project_be.application.usecases.response.GetClassTeachersResponse;
import graduation_project_be.application.usecases.CreateClassUsecase;
import graduation_project_be.application.usecases.UpdateClassUsecase;
import graduation_project_be.application.usecases.GetClassDetailUsecase;
import graduation_project_be.application.usecases.GetClassesUsecase;
import graduation_project_be.application.usecases.GetExamsByClassUsecase;
import graduation_project_be.application.usecases.GetStudentProgressInClassUsecase;
import graduation_project_be.application.usecases.GetStudentsInClassUsecase;
import graduation_project_be.application.usecases.SoftDeleteClassUsecase;
import graduation_project_be.application.usecases.RestoreClassUsecase;
import graduation_project_be.application.usecases.request.CreateClassRequest;
import graduation_project_be.application.usecases.request.GetStudentProgressInClassRequest;
import graduation_project_be.application.usecases.request.UpdateClassRequest;
import graduation_project_be.application.usecases.request.GetStudentsInClassRequest;
import graduation_project_be.application.usecases.response.*;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
public class ClassController {

        private final CreateClassUsecase createClassUsecase;
        private final UpdateClassUsecase updateClassUsecase;
        private final GetClassesUsecase getClassesUsecase;
        private final GetStudentsInClassUsecase getStudentsInClassUsecase;
        private final GetClassDetailUsecase getClassDetailUsecase;
        private final GetExamsByClassUsecase getExamsByClassUsecase;
        private final GetStudentProgressInClassUsecase getStudentProgressInClassUsecase;
        private final SoftDeleteClassUsecase softDeleteClassUsecase;
        private final RestoreClassUsecase restoreClassUsecase;
        private final BanStudentUsecase banStudentUsecase;
        private final UnbanStudentUsecase unbanStudentUsecase;
        private final GetClassBansUsecase getClassBansUsecase;
        private final AddTeacherToClassUsecase addTeacherToClassUsecase;
        private final GetClassTeachersUsecase getClassTeachersUsecase;
        private final RemoveTeacherFromClassUsecase removeTeacherFromClassUsecase;

        @PostMapping
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> createClass(
                        @RequestBody @Valid CreateClassRequestDto requestDto) {
                CreateClassRequest usecaseRequest = requestDto.toRequest();
                CreateClassResponse usecaseResponse = createClassUsecase.execute(usecaseRequest);
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(CreateClassResponseDto.fromResponse(usecaseResponse), "CREATED",
                                                "Tạo lớp học thành công!"));
        }

        @PutMapping("/{classId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> updateClass(
                        @PathVariable("classId") Long classId,
                        @RequestBody @Valid UpdateClassRequestDto requestDto) {
                UpdateClassRequest usecaseRequest = requestDto.toRequest(classId);
                CreateClassResponse usecaseResponse = updateClassUsecase.execute(usecaseRequest);
                return ResponseEntity.ok()
                                .body(ResponseDto.of(CreateClassResponseDto.fromResponse(usecaseResponse), "OK",
                                                "Cập nhật lớp học thành công!"));
        }


        @GetMapping
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<PaginationResponseDto<GetClassesResponseDto>> getClasses(
                        @RequestParam(name = "page", defaultValue = "1") int page,
                        @RequestParam(name = "size", defaultValue = "10") int size,
                        @RequestParam(name = "sortBy", defaultValue = "CREATED_AT") String sortBy,
                        @RequestParam(name = "sortOrder", defaultValue = "DESC") String sortOrder) {
                GetClassesRequestDto requestDto = GetClassesRequestDto.builder()
                                .page(page)
                                .size(size)
                                .sortBy(sortBy)
                                .sortOrder(sortOrder)
                                .build();

                PaginationResponse<GetClassesResponse> usecaseResponse = getClassesUsecase
                                .execute(requestDto.toRequest());

                return ResponseEntity.ok()
                                .body(PaginationResponseDto.fromResponse(usecaseResponse,
                                                GetClassesResponseDto::fromResponse, "OK",
                                                "Classes retrieved successfully"));
        }

        @GetMapping("/{classId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getClassDetail(
                        @PathVariable("classId") Long classId) {
                GetClassDetailRequestDto requestDto = GetClassDetailRequestDto.builder()
                                .classId(classId)
                                .build();

                GetClassDetailResponse usecaseResponse = getClassDetailUsecase.execute(requestDto.toRequest());
                return ResponseEntity.ok()
                                .body(ResponseDto.of(GetClassDetailResponseDto.fromResponse(usecaseResponse), "OK",
                                                "Class detail retrieved successfully"));
        }

        @GetMapping("/{classId}/students")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<PaginationResponseDto<GetStudentsInClassResponseDto>> getStudentsInClass(
                        @PathVariable("classId") Long classId,
                        @RequestParam(name = "page", defaultValue = "1") int page,
                        @RequestParam(name = "size", defaultValue = "10") int size,
                        @RequestParam(name = "sortBy", defaultValue = "FULL_NAME") String sortBy,
                        @RequestParam(name = "sortOrder", defaultValue = "ASC") String sortOrder) {
                GetStudentsInClassRequestDto requestDto = GetStudentsInClassRequestDto.builder()
                                .classId(classId)
                                .page(page)
                                .size(size)
                                .sortBy(sortBy)
                                .sortOrder(sortOrder)
                                .build();

                GetStudentsInClassRequest usecaseRequest = requestDto.toRequest();
                PaginationResponse<GetStudentsInClassResponse> usecaseResponse = getStudentsInClassUsecase
                                .execute(usecaseRequest);

                return ResponseEntity.ok()
                                .body(PaginationResponseDto.fromResponse(usecaseResponse,
                                                GetStudentsInClassResponseDto::fromResponse, "OK",
                                                "Students retrieved successfully"));
        }

        @GetMapping("/{classId}/exams")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getExamsByClass(
                        @PathVariable("classId") Long classId) {
                List<CreateExamResponse> responses = getExamsByClassUsecase.execute(classId);
                List<CreateExamResponseDto> dtos = responses.stream()
                                .map(CreateExamResponseDto::fromResponse)
                                .toList();
                return ResponseEntity.ok(
                                ResponseDto.of(dtos, "OK", "Exams retrieved successfully"));
        }

        @GetMapping("/{classId}/students/{studentId}/progress")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getStudentProgressInClass(
                        @PathVariable("classId") Long classId,
                        @PathVariable("studentId") Long studentId) {
                GetStudentProgressInClassResponse response = getStudentProgressInClassUsecase.execute(
                                new GetStudentProgressInClassRequest(classId, studentId));
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                GetStudentProgressInClassResponseDto.fromResponse(response),
                                                "OK",
                                                "Student progress retrieved successfully"));
        }

        @DeleteMapping("/{classId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> softDeleteClass(
                        @PathVariable("classId") Long classId) {
                softDeleteClassUsecase.execute(classId);
                return ResponseEntity.ok(ResponseDto.of(null, "OK", "Xóa lớp học thành công!"));
        }

        @PostMapping("/{classId}/restore")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> restoreClass(
                        @PathVariable("classId") Long classId) {
                restoreClassUsecase.execute(classId);
                return ResponseEntity.ok(ResponseDto.of(null, "OK", "Khôi phục lớp học thành công!"));
        }

        @GetMapping("/{classId}/bans")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<PaginationResponseDto<BannedStudentResponseDto>> getClassBans(
                        @PathVariable("classId") Long classId,
                        @RequestParam(name = "page", defaultValue = "1") int page,
                        @RequestParam(name = "size", defaultValue = "10") int size) {
                PaginationResponse<BannedStudentResponse> response = getClassBansUsecase.execute(classId, page, size);
                return ResponseEntity.ok(PaginationResponseDto.fromResponse(
                                response, BannedStudentResponseDto::fromResponse, "OK",
                                "Banned students retrieved successfully"));
        }

        @PostMapping("/{classId}/bans")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> banStudent(
                        @PathVariable("classId") Long classId,
                        @RequestBody @Valid BanStudentRequestDto requestDto) {
                banStudentUsecase.execute(requestDto.toRequest(classId));
                return ResponseEntity.ok(ResponseDto.of(null, "OK", "Student banned successfully"));
        }

        @DeleteMapping("/{classId}/bans/{studentId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> unbanStudent(
                        @PathVariable("classId") Long classId,
                        @PathVariable("studentId") Long studentId) {
                unbanStudentUsecase.execute(classId, studentId);
                return ResponseEntity.ok(ResponseDto.of(null, "OK", "Student unbanned successfully"));
        }

        @GetMapping("/{classId}/teachers")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getClassTeachers(@PathVariable("classId") Long classId) {
                List<GetClassTeachersResponse> responses = getClassTeachersUsecase.execute(classId);
                List<ClassTeacherResponseDto> dtos = responses.stream()
                                .map(ClassTeacherResponseDto::fromResponse)
                                .toList();
                return ResponseEntity.ok(ResponseDto.of(dtos, "OK", "Class teachers retrieved successfully"));
        }

        @PostMapping("/{classId}/teachers")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> addTeacherToClass(
                        @PathVariable("classId") Long classId,
                        @RequestBody @Valid AddTeacherToClassRequestDto requestDto) {
                AddTeacherToClassRequest request = requestDto.toRequest(classId);
                addTeacherToClassUsecase.execute(request);
                return ResponseEntity.ok(ResponseDto.of(null, "OK", "Thêm giảng viên vào lớp học thành công!"));
        }

        @DeleteMapping("/{classId}/teachers/{teacherId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> removeTeacherFromClass(
                        @PathVariable("classId") Long classId,
                        @PathVariable("teacherId") Long teacherId) {
                removeTeacherFromClassUsecase.execute(classId, teacherId);
                return ResponseEntity.ok(ResponseDto.of(null, "OK", "Xóa giảng viên khỏi lớp học thành công!"));
        }
}
