package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.CreateExamQuestionRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.CreateExamRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.ExecuteSqlRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.GetStudentExamDetailRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.ReportViolationRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.StartExamSessionRequestDto;
import graduation_project_be.adapter.web.api.dtos.request.SubmitExamRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.*;
import graduation_project_be.application.usecases.*;
import graduation_project_be.application.usecases.request.CreateExamRequest;
import graduation_project_be.application.usecases.response.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
@Validated
public class ExamController {

        private final CreateExamUsecase createExamUsecase;
        private final GetStudentExamUsecase getStudentExamUsecase;
        private final CreateExamQuestionUsecase createExamQuestionUsecase;
        private final GetExamQuestionsUsecase getExamQuestionsUsecase;
        private final GetStudentExamsUsecase getStudentExamsUsecase;
        private final ExecuteSqlUsecase executeSqlUsecase;
        private final SubmitExamUsecase submitExamUsecase;
        private final ReportViolationUsecase reportViolationUsecase;
        private final GetViolationsUsecase getViolationsUsecase;
        private final StartExamSessionUsecase startExamSessionUsecase;
        private final GetExamTimeUsecase getExamTimeUsecase;

        // ===== TEACHER ENDPOINTS =====

        @PostMapping
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> createExam(
                        @RequestBody @Valid CreateExamRequestDto requestDto) {
                CreateExamRequest request = requestDto.toRequest();
                CreateExamResponse response = createExamUsecase.execute(request);
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(
                                                CreateExamResponseDto.fromResponse(response),
                                                "CREATED",
                                                "Exam created successfully"));
        }

        @PostMapping("/{examId}/questions")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> createExamQuestion(
                        @PathVariable @Positive Long examId,
                        @RequestBody @Valid CreateExamQuestionRequestDto requestDto) {
                ExamQuestionResponse response = createExamQuestionUsecase.execute(requestDto.toRequest(examId));
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(
                                                ExamQuestionResponseDto.fromResponse(response),
                                                "CREATED",
                                                "Question created successfully"));
        }

        @GetMapping("/{examId}/questions")
        @PreAuthorize("hasAnyRole('TEACHER', 'STUDENT')")
        public ResponseEntity<ResponseDto> getExamQuestions(
                        @PathVariable @Positive Long examId) {
                List<ExamQuestionResponse> responses = getExamQuestionsUsecase.execute(examId);

                // Return different DTO based on role
                var auth = org.springframework.security.core.context.SecurityContextHolder
                                .getContext().getAuthentication();
                boolean isTeacher = auth.getAuthorities().stream()
                                .anyMatch(a -> a.getAuthority().equals("ROLE_TEACHER"));

                if (isTeacher) {
                        // Teacher sees full question details (correctQuery, verifyScript)
                        List<ExamQuestionResponseDto> dtos = responses.stream()
                                        .map(ExamQuestionResponseDto::fromResponse)
                                        .toList();
                        return ResponseEntity.ok(
                                        ResponseDto.of(dtos, "OK", "Questions retrieved successfully"));
                } else {
                        // Student sees only content/metadata (no answers)
                        List<StudentExamQuestionResponseDto> dtos = responses.stream()
                                        .map(StudentExamQuestionResponseDto::fromResponse)
                                        .toList();
                        return ResponseEntity.ok(
                                        ResponseDto.of(dtos, "OK", "Questions retrieved successfully"));
                }
        }

        // ===== STUDENT ENDPOINTS =====

        @GetMapping("/{examId}")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> getExamDetail(
                        @PathVariable @Positive Long examId) {
                GetStudentExamDetailRequestDto requestDto = GetStudentExamDetailRequestDto.builder()
                                .examId(examId)
                                .build();
                GetStudentExamResponse response = getStudentExamUsecase.execute(requestDto.toRequest());
                return ResponseEntity.ok(
                                ResponseDto.of(GetStudentExamResponseDto.fromResponse(response), "OK",
                                                "Exam retrieved successfully"));
        }

        @GetMapping("/enrolled")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> getStudentExams() {
                List<StudentExamListResponse> responses = getStudentExamsUsecase.execute();
                List<StudentExamListResponseDto> dtos = responses.stream()
                                .map(StudentExamListResponseDto::fromResponse)
                                .toList();
                return ResponseEntity.ok(
                                ResponseDto.of(dtos, "OK", "Student exams retrieved successfully"));
        }

        @PostMapping("/{examId}/execute-sql")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> executeSql(
                        @PathVariable @Positive Long examId,
                        @RequestBody @Valid ExecuteSqlRequestDto requestDto) {
                ExecuteSqlResponse response = executeSqlUsecase.execute(requestDto.toRequest(examId));
                return ResponseEntity.ok(
                                ResponseDto.of(ExecuteSqlResponseDto.fromResponse(response), "OK", "SQL executed"));
        }

        /**
         * Submit entire exam — grades all answers in one request.
         * Body: { "answers": [ { "questionId": 1, "studentQuery": "..." }, ... ] }
         * Returns: total score + per-question breakdown.
         */
        @PostMapping("/{examId}/submit")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> submitExam(
                        @PathVariable @Positive Long examId,
                        @RequestBody @Valid SubmitExamRequestDto requestDto) {
                SubmitExamResponse response = submitExamUsecase.execute(requestDto.toRequest(examId));
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(
                                                SubmitExamResponseDto.fromResponse(response),
                                                "CREATED",
                                                "Exam submitted and graded successfully"));
        }

        // ===== ANTI-CHEATING ENDPOINTS =====

        @PostMapping("/{examId}/start-session")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> startExamSession(
                        @PathVariable @Positive Long examId,
                        HttpServletRequest httpRequest) {
                String ipAddress = getClientIp(httpRequest);
                String userAgent = httpRequest.getHeader("User-Agent");
                StartExamSessionRequestDto requestDto = new StartExamSessionRequestDto();
                StartExamSessionResponse response = startExamSessionUsecase
                                .execute(requestDto.toRequest(examId, ipAddress, userAgent));
                return ResponseEntity.ok(
                                ResponseDto.of(StartExamSessionResponseDto.fromResponse(response), "OK",
                                                "Exam session started successfully"));
        }

        @PostMapping("/{examId}/violations")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> reportViolation(
                        @PathVariable @Positive Long examId,
                        @RequestBody @Valid ReportViolationRequestDto requestDto,
                        HttpServletRequest httpRequest) {
                String ipAddress = getClientIp(httpRequest);
                String userAgent = httpRequest.getHeader("User-Agent");
                ReportViolationResponse response = reportViolationUsecase
                                .execute(requestDto.toRequest(examId, ipAddress, userAgent));
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(
                                                ReportViolationResponseDto.fromResponse(response),
                                                "CREATED",
                                                response.message()));
        }

        @GetMapping("/{examId}/violations")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getViolations(
                        @PathVariable @Positive Long examId,
                        @RequestParam(required = false) Long studentId) {
                List<ExamViolationResponse> responses = getViolationsUsecase.execute(examId, studentId);
                List<ExamViolationResponseDto> dtos = responses.stream()
                                .map(ExamViolationResponseDto::fromResponse)
                                .toList();
                return ResponseEntity.ok(
                                ResponseDto.of(dtos, "OK", "Violations retrieved successfully"));
        }

        /**
         * Get server-authoritative exam time.
         * Client polls this endpoint to sync countdown timer.
         * Prevents DevTools time manipulation.
         */
        @GetMapping("/{examId}/time")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> getExamTime(
                        @PathVariable @Positive Long examId) {
                ExamTimeResponse response = getExamTimeUsecase.execute(examId);
                return ResponseEntity.ok(
                                ResponseDto.of(ExamTimeResponseDto.fromResponse(response), "OK",
                                                "Exam time retrieved successfully"));
        }

        // ===== HELPER =====

        private String getClientIp(HttpServletRequest request) {
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                        return xForwardedFor.split(",")[0].trim();
                }
                return request.getRemoteAddr();
        }
}
