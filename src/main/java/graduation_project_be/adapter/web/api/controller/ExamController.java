package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.*;
import graduation_project_be.adapter.web.api.dtos.response.*;
import graduation_project_be.application.usecases.*;
import graduation_project_be.application.usecases.request.CreateExamRequest;
import graduation_project_be.application.usecases.request.UpdateExamRequest;
import graduation_project_be.application.usecases.response.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
@Validated
public class ExamController {

        private final CreateExamUsecase createExamUsecase;
        private final GetStudentExamUsecase getStudentExamUsecase;
        private final CreateExamQuestionsUsecase createExamQuestionsUsecase;
        private final GetExamQuestionsUsecase getExamQuestionsUsecase;
        private final GetStudentExamsUsecase getStudentExamsUsecase;
        private final ExecuteSqlUsecase executeSqlUsecase;
        private final SubmitExamUsecase submitExamUsecase;
        private final ReportViolationUsecase reportViolationUsecase;
        private final GetViolationsUsecase getViolationsUsecase;
        private final StartExamSessionUsecase startExamSessionUsecase;
        private final GetExamTimeUsecase getExamTimeUsecase;
        private final SaveExamSpecificationUsecase saveExamSpecificationUsecase;
        private final GetExamSpecificationUsecase getExamSpecificationUsecase;
        private final UpdateExamUsecase updateExamUsecase;
        private final UpdateExamQuestionUsecase updateExamQuestionUsecase;
        private final DeleteExamQuestionUsecase deleteExamQuestionUsecase;

        private final GetTeacherExamDetailUsecase getTeacherExamDetailUsecase;
        private final GetExamResultsUsecase getExamResultsUsecase;
        private final GetExamResultDetailUsecase getExamResultDetailUsecase;
        private final GetExamMonitorUsecase getExamMonitorUsecase;
        private final RubricTestingUsecase rubricTestingUsecase;
        private final ObjectMapper objectMapper;
        private final GetTeacherExamSettingsUsecase getTeacherExamSettingsUsecase;
        private final GetTeacherExamTemplateVersionsUsecase getTeacherExamTemplateVersionsUsecase;
        private final UpdateTeacherExamSettingsUsecase updateTeacherExamSettingsUsecase;
        private final SaveExamDraftUsecase saveExamDraftUsecase;
        private final GetExamDraftUsecase getExamDraftUsecase;
        private final ApproveDeviceConflictUsecase approveDeviceConflictUsecase;
        private final RejectDeviceConflictUsecase rejectDeviceConflictUsecase;
        private final RegradeExamUsecase regradeExamUsecase;
        private final RegradeAllExamUsecase regradeAllExamUsecase;
        private final OverrideSubmissionScoreUsecase overrideSubmissionScoreUsecase;

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

        @PutMapping("/{examId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> updateExam(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid UpdateExamRequestDto requestDto) {
                UpdateExamRequest request = requestDto.toRequest(examId);
                UpdateExamResponse response = updateExamUsecase.execute(request);
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                UpdateExamResponseDto.fromResponse(response),
                                                "OK",
                                                "Exam updated successfully"));
        }

        @GetMapping("/{examId}/teacher-detail")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getTeacherExamDetail(
                        @PathVariable("examId") @Positive Long examId) {
                GetTeacherExamDetailRequestDto requestDto = new GetTeacherExamDetailRequestDto(examId);
                GetTeacherExamDetailResponse response = getTeacherExamDetailUsecase
                                .execute(requestDto.toRequest());
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                GetTeacherExamDetailResponseDto.fromResponse(response),
                                                "OK",
                                                "Exam detail retrieved successfully"));
        }

        @GetMapping("/{examId}/monitor")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getExamMonitor(
                        @PathVariable("examId") @Positive Long examId) {
                GetExamMonitorRequestDto requestDto = new GetExamMonitorRequestDto(examId);
                GetExamMonitorResponse response = getExamMonitorUsecase.execute(requestDto.toRequest());
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                GetExamMonitorResponseDto.fromResponse(response),
                                                "OK",
                                                "Exam monitor data retrieved successfully"));
        }

        @PostMapping("/{examId}/questions")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> createExamQuestions(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid CreateExamQuestionsRequestDto requestDto) {
                CreateExamQuestionsResponse response = createExamQuestionsUsecase.execute(requestDto.toRequest(examId));
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(
                                                CreateExamQuestionsResponseDto.fromResponse(response),
                                                "CREATED",
                                                response.totalCreated() + " question(s) created successfully"));
        }

        @PutMapping("/{examId}/questions/{questionId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> updateExamQuestion(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("questionId") @Positive Long questionId,
                        @RequestBody @Valid UpdateExamQuestionRequestDto requestDto) {
                ExamQuestionResponse response = updateExamQuestionUsecase.execute(requestDto.toRequest(examId, questionId));
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                ExamQuestionResponseDto.fromResponse(response),
                                                "OK",
                                                "Question updated successfully"));
        }

        @DeleteMapping("/{examId}/questions/{questionId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> deleteExamQuestion(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("questionId") @Positive Long questionId) {
                deleteExamQuestionUsecase.execute(examId, questionId);
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                null,
                                                "OK",
                                                "Question deleted successfully"));
        }


        @PostMapping("/{examId}/specification")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> saveSpecification(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid SaveExamSpecificationRequestDto requestDto) {
                ExamSpecificationResponse response = saveExamSpecificationUsecase.execute(requestDto.toRequest(examId));
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(
                                                ExamSpecificationResponseDto.fromResponse(response),
                                                "CREATED",
                                                "Exam specification saved successfully"));
        }

        @GetMapping("/{examId}/specification")
        @PreAuthorize("hasAnyRole('TEACHER', 'STUDENT')")
        public ResponseEntity<ResponseDto> getSpecification(
                        @PathVariable("examId") @Positive Long examId) {
                ExamSpecificationResponse response = getExamSpecificationUsecase.execute(examId);
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                ExamSpecificationResponseDto.fromResponse(response),
                                                "OK",
                                                "Exam specification retrieved successfully"));
        }

        @GetMapping("/{examId}/settings")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getTeacherExamSettings(
                        @PathVariable("examId") @Positive Long examId) {
                CreateExamResponse response = getTeacherExamSettingsUsecase.execute(examId);
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                CreateExamResponseDto.fromResponse(response),
                                                "OK",
                                                "Exam settings retrieved successfully"));
        }

        @PutMapping("/{examId}/settings")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> updateTeacherExamSettings(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid UpdateTeacherExamSettingsRequestDto requestDto) {
                CreateExamResponse response = updateTeacherExamSettingsUsecase.execute(examId, requestDto.toRequest());
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                CreateExamResponseDto.fromResponse(response),
                                                "OK",
                                                "Exam settings updated successfully"));
        }

        @GetMapping("/{examId}/template-versions")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getTeacherExamTemplateVersions(
                        @PathVariable("examId") @Positive Long examId) {
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                TeacherExamTemplateVersionsResponseDto.fromResponse(
                                                                getTeacherExamTemplateVersionsUsecase.execute(examId)),
                                                "OK",
                                                "Exam template history retrieved successfully"));
        }

        @GetMapping("/{examId}/results")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getExamResults(
                        @PathVariable("examId") @Positive Long examId) {
                List<GetExamResultsResponse> responses = getExamResultsUsecase.execute(examId);
                List<GetExamResultsResponseDto> dtos = responses.stream()
                                .map(GetExamResultsResponseDto::fromResponse)
                                .toList();
                return ResponseEntity.ok(
                                ResponseDto.of(dtos, "OK", "Exam results retrieved successfully"));
        }

        @GetMapping("/{examId}/results/{resultId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getExamResultDetail(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("resultId") @Positive Long resultId) {
                GetExamResultDetailResponse response = getExamResultDetailUsecase.execute(examId, resultId);
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                GetExamResultDetailResponseDto.fromResponse(response),
                                                "OK",
                                                "Exam result detail retrieved successfully"));
        }

        @PatchMapping("/{examId}/results/{resultId}/submissions/{submissionId}/override")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> overrideSubmissionScore(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("resultId") @Positive Long resultId,
                        @PathVariable("submissionId") @Positive Long submissionId,
                        @RequestBody @Valid OverrideSubmissionRequestDto requestDto) {
                OverrideSubmissionScoreResponse response = overrideSubmissionScoreUsecase.execute(
                                examId, resultId, submissionId, requestDto.toRequest());
                return ResponseEntity.ok(ResponseDto.of(
                                OverrideSubmissionResponseDto.fromResponse(response),
                                "OK", "Score overridden successfully"));
        }

        @PostMapping("/{examId}/results/{resultId}/regrade")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> regradeExamResult(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("resultId") @Positive Long resultId) {
                RegradeExamResponse response = regradeExamUsecase.execute(examId, resultId);
                return ResponseEntity.accepted().body(ResponseDto.of(
                                RegradeExamResponseDto.fromResponse(response),
                                "ACCEPTED", "Re-grading started"));
        }

        @PostMapping("/{examId}/regrade-all")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> regradeAllExamResults(
                        @PathVariable("examId") @Positive Long examId) {
                RegradeAllExamResponse response = regradeAllExamUsecase.execute(examId);
                return ResponseEntity.accepted().body(ResponseDto.of(
                                RegradeAllExamResponseDto.fromResponse(response),
                                "ACCEPTED", response.message()));
        }

        @GetMapping("/{examId}/questions")
        @PreAuthorize("hasAnyRole('TEACHER', 'STUDENT')")
        public ResponseEntity<ResponseDto> getExamQuestions(
                        @PathVariable("examId") @Positive Long examId) {
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
                        @PathVariable("examId") @Positive Long examId) {
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
        @PreAuthorize("hasAnyRole('TEACHER', 'STUDENT')")
        public ResponseEntity<ResponseDto> executeSql(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid ExecuteSqlRequestDto requestDto,
                        HttpServletRequest httpRequest) {
                String ip = getClientIp(httpRequest);
                String ua = httpRequest.getHeader("User-Agent");
                ExecuteSqlResponse response = executeSqlUsecase.execute(requestDto.toRequest(examId, ip, ua));
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
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid SubmitExamRequestDto requestDto,
                        HttpServletRequest httpRequest) {
                String ip = getClientIp(httpRequest);
                String ua = httpRequest.getHeader("User-Agent");
                SubmitExamResponse response = submitExamUsecase.execute(requestDto.toRequest(examId, ip, ua));
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                                .body(ResponseDto.of(
                                                SubmitExamResponseDto.fromResponse(response),
                                                "ACCEPTED",
                                                "Exam submitted successfully. Grading in progress."));
        }

        // ===== ANTI-CHEATING ENDPOINTS =====

        @PostMapping("/{examId}/start-session")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> startExamSession(
                        @PathVariable("examId") @Positive Long examId,
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
                        @PathVariable("examId") @Positive Long examId,
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
                        @PathVariable("examId") @Positive Long examId,
                        @RequestParam(name = "studentId", required = false) Long studentId) {
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
                        @PathVariable("examId") @Positive Long examId) {
                ExamTimeResponse response = getExamTimeUsecase.execute(examId);
                return ResponseEntity.ok(
                                ResponseDto.of(ExamTimeResponseDto.fromResponse(response), "OK",
                                                "Exam time retrieved successfully"));
        }

        // ===== DEVICE CONFLICT ENDPOINTS =====

        @PostMapping("/{examId}/device-conflict/{conflictId}/approve")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> approveDeviceConflict(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("conflictId") String conflictId) {
                approveDeviceConflictUsecase.execute(examId, conflictId);
                return ResponseEntity.ok(
                                ResponseDto.of(null, "OK", "Device conflict approved. Student may now continue exam."));
        }

        @PostMapping("/{examId}/device-conflict/{conflictId}/reject")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> rejectDeviceConflict(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("conflictId") String conflictId,
                        @RequestBody(required = false) RejectDeviceConflictRequestDto requestDto) {
                String reason = requestDto != null ? requestDto.reason() : null;
                rejectDeviceConflictUsecase.execute(examId, conflictId, reason);
                return ResponseEntity.ok(
                                ResponseDto.of(null, "OK", "Device conflict rejected."));
        }

        // ===== HELPER =====

        private String getClientIp(HttpServletRequest request) {
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                        return xForwardedFor.split(",")[0].trim();
                }
                return request.getRemoteAddr();
        }

        // ===== AI RUBRIC GENERATION =====

        @PostMapping("/generate-rubric")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> generateGradingRubric(
                        @RequestBody @Valid GenerateGradingRubricRequestDto requestDto) {
                String rubricJson = rubricTestingUsecase.generateGradingRubric(requestDto.toRequest());

                if (rubricJson == null) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                        .body(ResponseDto.of(null, "AI_UNAVAILABLE",
                                                        "AI service is currently unavailable"));
                }

                return ResponseEntity.ok(
                                ResponseDto.of(rubricJson, "OK", "Rubric generated successfully"));
        }

        // ===== TEST GRADING =====

        @PostMapping("/{examId}/test-grade-insert")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> testGradeInsert(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid TestGradeInsertRequestDto requestDto) {
                try {
                        RubricTestGradeResponse result = rubricTestingUsecase.testGradeInsert(
                                        requestDto.toRequest(examId, objectMapper));
                        return ResponseEntity.ok(ResponseDto.of(result, "OK", "Test grading completed"));
                } catch (Exception e) {
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(ResponseDto.of(null, "GRADING_ERROR",
                                                        "Lỗi chấm thử: " + e.getMessage()));
                }
        }

        @PostMapping("/{examId}/test-grade-select")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> testGradeSelect(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid TestGradeSelectRequestDto requestDto) {
                try {
                        RubricTestGradeResponse result = rubricTestingUsecase.testGradeSelect(
                                        requestDto.toRequest(examId, objectMapper));
                        return ResponseEntity.ok(ResponseDto.of(result, "OK", "Test grading completed"));
                } catch (Exception e) {
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(ResponseDto.of(null, "GRADING_ERROR",
                                                        "Lỗi chấm thử SELECT: " + e.getMessage()));
                }
        }

        @PostMapping("/{examId}/run-select-testcase")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> runSelectTestcase(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid ExecuteSelectQueryRequestDto requestDto) {
                try {
                        ExecuteSelectTestCaseResponse result = rubricTestingUsecase
                                        .executeSelectTestCase(requestDto.toRequest(examId));
                        return ResponseEntity.ok(ResponseDto.of(
                                        ExecuteSelectTestCaseResponseDto.fromResponse(result),
                                        "OK",
                                        "Run testcase completed"));
                } catch (Exception e) {
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(ResponseDto.of(null, "EXECUTE_ERROR",
                                                        "Lỗi chạy thử dữ liệu: " + e.getMessage()));
                }
        }

        @PostMapping("/{examId}/build-insert-tables")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> buildInsertTables(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid BuildInsertTablesRequestDto requestDto) {
                try {
                        BuildInsertTablesResponse result = rubricTestingUsecase.buildInsertTablesFromAnswer(
                                        examId,
                                        requestDto.correctQuery());
                        return ResponseEntity.ok(ResponseDto.of(
                                        BuildInsertTablesResponseDto.fromResponse(result),
                                        "OK",
                                        "Build insert tables completed"));
                } catch (Exception e) {
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(ResponseDto.of(null, "EXECUTE_ERROR",
                                                        "Lỗi tạo dữ liệu tables INSERT: " + e.getMessage()));
                }
        }

        @PostMapping("/test-grade")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> testGrade(
                        @RequestBody @Valid TestGradeCreateTableRequestDto requestDto) {
                try {
                        RubricTestGradeResponse result = rubricTestingUsecase.testGradeCreateTable(
                                        requestDto.toRequest(objectMapper));
                        return ResponseEntity.ok(ResponseDto.of(result, "OK", "Test grading completed"));
                } catch (Exception e) {
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(ResponseDto.of(null, "GRADING_ERROR",
                                                        "Lỗi chấm thử: " + e.getMessage()));
                }
        }

        // ===== DRAFT ENDPOINTS =====

        @PutMapping("/{examId}/draft")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> saveExamDraft(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid SaveExamDraftRequestDto requestDto) {
                SaveExamDraftResponse response = saveExamDraftUsecase.execute(requestDto.toRequest(examId));
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                SaveExamDraftResponseDto.fromResponse(response),
                                                "OK",
                                                "Draft saved successfully"));
        }

        @GetMapping("/{examId}/draft")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> getExamDraft(
                        @PathVariable("examId") @Positive Long examId) {
                return getExamDraftUsecase.execute(examId)
                                .map(resp -> ResponseEntity.ok(
                                                ResponseDto.of(
                                                                GetExamDraftResponseDto.fromResponse(resp),
                                                                "OK",
                                                                "Draft retrieved successfully")))
                                .orElseGet(() -> ResponseEntity.ok(
                                                ResponseDto.of(null, "NOT_FOUND", "No draft found")));
        }
}
