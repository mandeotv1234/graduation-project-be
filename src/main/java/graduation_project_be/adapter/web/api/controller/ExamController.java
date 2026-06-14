package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.*;
import graduation_project_be.adapter.web.api.dtos.response.*;
import graduation_project_be.application.port.services.PdfStorageService;
import graduation_project_be.application.usecases.*;
import graduation_project_be.application.usecases.request.*;
import graduation_project_be.application.usecases.response.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
@Validated
public class ExamController {

        private final CreateExamUsecase createExamUsecase;
        private final GetStudentExamUsecase getStudentExamUsecase;
        private final GetStudentExamsUsecase getStudentExamsUsecase;
        private final GetStudentResultsUsecase getStudentResultsUsecase;
        private final GetMyResultDetailUsecase getMyResultDetailUsecase;
        private final GetStudentFeedbackUsecase getStudentFeedbackUsecase;
        private final CreateExamQuestionsUsecase createExamQuestionsUsecase;
        private final GetExamQuestionsUsecase getExamQuestionsUsecase;
        private final ExecuteSqlUsecase executeSqlUsecase;
        private final SubmitExamUsecase submitExamUsecase;
        private final ReportViolationUsecase reportViolationUsecase;
        private final RecordHeartbeatUsecase recordHeartbeatUsecase;
        private final GetViolationsUsecase getViolationsUsecase;
        private final StartExamSessionUsecase startExamSessionUsecase;
        private final GetExamTimeUsecase getExamTimeUsecase;
        private final SaveExamSpecificationUsecase saveExamSpecificationUsecase;
        private final GetExamSpecificationUsecase getExamSpecificationUsecase;
        private final UpdateExamUsecase updateExamUsecase;
        private final UpdateExamQuestionUsecase updateExamQuestionUsecase;
        private final DeleteExamQuestionUsecase deleteExamQuestionUsecase;
        private final DeleteExamUsecase deleteExamUsecase;
        private final ClearExamSchemaUsecase clearExamSchemaUsecase;
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
        private final PdfStorageService pdfStorageService;
        private final DownloadExamPdfUsecase downloadExamPdfUsecase;
        private final RemindStudentUsecase remindStudentUsecase;
        private final ForceSubmitExamUsecase forceSubmitExamUsecase;
        private final GetExamStatisticsUsecase getExamStatisticsUsecase;
        private final ExportExamPdfUsecase exportExamPdfUsecase;
        private final ExtractQuestionsFromPdfUsecase extractQuestionsFromPdfUsecase;
        private final TeacherExecuteSqlOnResultUsecase teacherExecuteSqlOnResultUsecase;
        private final TeacherResetResultSchemaUsecase teacherResetResultSchemaUsecase;
        private final DropAllExamSchemasUsecase dropAllExamSchemasUsecase;
        private final WhiteboxCatalogUsecase whiteboxCatalogUsecase;
        private final WhiteboxValidateUsecase whiteboxValidateUsecase;
        private final GetExamPreviewUsecase getExamPreviewUsecase;
        private final InitializePreviewSchemaUsecase initializePreviewSchemaUsecase;
        private final ClearPreviewSchemaUsecase clearPreviewSchemaUsecase;
        private final PreviewSubmitExamUsecase previewSubmitExamUsecase;

        // ===== TEACHER ENDPOINTS =====

        @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> createExam(
                        @RequestPart("data") @Valid CreateExamRequestDto requestDto,
                        @RequestPart(value = "pdfFile", required = false) MultipartFile pdfFile) {

                String pdfFilePath = null;
                String originalPdfFileName = null;

                if (pdfFile != null && !pdfFile.isEmpty()) {
                        if (!"application/pdf".equals(pdfFile.getContentType())) {
                                return ResponseEntity.badRequest()
                                                .body(ResponseDto.of(null, "BAD_REQUEST",
                                                                "File must be a PDF"));
                        }
                        if (pdfFile.getSize() > 10 * 1024 * 1024) {
                                return ResponseEntity.badRequest()
                                                .body(ResponseDto.of(null, "BAD_REQUEST",
                                                                "PDF file size must not exceed 10MB"));
                        }

                        pdfFilePath = pdfStorageService.savePdf(pdfFile);
                        originalPdfFileName = pdfFile.getOriginalFilename();
                }

                CreateExamRequest request = requestDto.toRequest(pdfFilePath, originalPdfFileName);
                CreateExamResponse response = createExamUsecase.execute(request);
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(
                                                CreateExamResponseDto.fromResponse(response),
                                                "CREATED",
                                                "Tạo đề thi thành công!"));
        }

        @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> createExamJson(
                        @RequestBody @Valid CreateExamRequestDto requestDto) {
                CreateExamRequest request = requestDto.toRequest();
                CreateExamResponse response = createExamUsecase.execute(request);
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ResponseDto.of(
                                                CreateExamResponseDto.fromResponse(response),
                                                "CREATED",
                                                "Tạo đề thi thành công!"));
        }

        @PutMapping(value = "/{examId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> updateExamMultipart(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestPart("data") @Valid UpdateExamRequestDto requestDto,
                        @RequestPart(value = "pdfFile", required = false) MultipartFile pdfFile) {

                String pdfFilePath = null;
                String originalPdfFileName = null;

                if (pdfFile != null && !pdfFile.isEmpty()) {
                        if (!"application/pdf".equals(pdfFile.getContentType())) {
                                return ResponseEntity.badRequest()
                                                .body(ResponseDto.of(null, "BAD_REQUEST",
                                                                "File must be a PDF"));
                        }
                        if (pdfFile.getSize() > 10 * 1024 * 1024) {
                                return ResponseEntity.badRequest()
                                                .body(ResponseDto.of(null, "BAD_REQUEST",
                                                                "PDF file size must not exceed 10MB"));
                        }

                        pdfFilePath = pdfStorageService.savePdf(pdfFile);
                        originalPdfFileName = pdfFile.getOriginalFilename();
                }

                UpdateExamRequest request = requestDto.toRequest(examId, pdfFilePath, originalPdfFileName);
                UpdateExamResponse response = updateExamUsecase.execute(request);
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                UpdateExamResponseDto.fromResponse(response),
                                                "OK",
                                                "Cập nhật đề thi thành công!"));
        }

        @PutMapping(value = "/{examId}", consumes = MediaType.APPLICATION_JSON_VALUE)
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
                                                "Cập nhật đề thi thành công!"));
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
                        @PathVariable("examId") @Positive Long examId,
                        @RequestParam(name = "page", defaultValue = "1") int page,
                        @RequestParam(name = "size", defaultValue = "10") int size,
                        @RequestParam(name = "keyword", defaultValue = "") String keyword,
                        @RequestParam(name = "riskFilter", defaultValue = "all") String riskFilter,
                        @RequestParam(name = "examStatusFilter", defaultValue = "IN_PROGRESS") String examStatusFilter,
                        @RequestParam(name = "highRiskThreshold", defaultValue = "3") int highRiskThreshold,
                        @RequestParam(name = "sortColumn", defaultValue = "violationCount") String sortColumn,
                        @RequestParam(name = "sortDirection", defaultValue = "desc") String sortDirection) {
                GetExamMonitorRequestDto requestDto = new GetExamMonitorRequestDto(
                                examId,
                                page,
                                size,
                                keyword,
                                riskFilter,
                                examStatusFilter,
                                highRiskThreshold,
                                sortColumn,
                                sortDirection);
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
                                                response.totalCreated() + " câu hỏi đã được tạo thành công!"));
        }

        @PutMapping("/{examId}/questions/{questionId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> updateExamQuestion(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("questionId") @Positive Long questionId,
                        @RequestBody @Valid UpdateExamQuestionRequestDto requestDto) {
                ExamQuestionResponse response = updateExamQuestionUsecase
                                .execute(requestDto.toRequest(examId, questionId));
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                ExamQuestionResponseDto.fromResponse(response),
                                                "OK",
                                                "Cập nhật câu hỏi thành công!"));
        }

        @DeleteMapping("/{examId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> deleteExam(
                        @PathVariable("examId") @Positive Long examId) {
                deleteExamUsecase.execute(examId);
                return ResponseEntity.ok(
                                ResponseDto.of(
                                                null,
                                                "OK",
                                                "Xóa đề thi thành công!"));
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
                                                "Xóa câu hỏi thành công!"));
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
        public ResponseEntity<PaginationResponseDto<GetExamResultsResponseDto>> getExamResults(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestParam(name = "page", defaultValue = "1") int page,
                        @RequestParam(name = "size", defaultValue = "5") int size,
                        @RequestParam(name = "keyword", defaultValue = "") String keyword,
                        @RequestParam(name = "scoreFilter", defaultValue = "all") String scoreFilter,
                        @RequestParam(name = "encounterMode", defaultValue = "all") String encounterMode,
                        @RequestParam(name = "sortOrder", defaultValue = "timeDesc") String sortOrder) {
                GetExamResultsRequest request = new GetExamResultsRequest(
                                examId,
                                page,
                                size,
                                keyword,
                                scoreFilter,
                                encounterMode,
                                sortOrder);
                PaginationResponse<GetExamResultsResponse> responses = getExamResultsUsecase.execute(request);
                return ResponseEntity.ok(
                                PaginationResponseDto.fromResponse(
                                                responses,
                                                GetExamResultsResponseDto::fromResponse,
                                                "OK",
                                                "Exam results retrieved successfully"));
        }

        @GetMapping("/{examId}/statistics")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getExamStatistics(
                        @PathVariable("examId") @Positive Long examId) {
                GetExamStatisticsResponseDto dto = GetExamStatisticsResponseDto
                                .fromResponse(getExamStatisticsUsecase.execute(examId));
                return ResponseEntity.ok(
                                ResponseDto.of(dto, "OK", "Exam statistics retrieved successfully"));
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
                                "OK", "Cập nhật điểm thành công!"));
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

        @GetMapping("/{examId}/pdf")
        @PreAuthorize("hasAnyRole('TEACHER', 'STUDENT')")
        public ResponseEntity<byte[]> downloadExamPdf(
                        @PathVariable("examId") @Positive Long examId) {
                DownloadExamPdfResponse response = downloadExamPdfUsecase.execute(examId);
                return ResponseEntity.ok()
                                .contentType(MediaType.APPLICATION_PDF)
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "inline; filename=\"" + response.fileName() + "\"")
                                .body(response.content());
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

        @GetMapping("/my-results")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> getMyResults(
                        @Valid GetStudentResultsRequestDto requestDto) {
                GetStudentResultsRequest request = requestDto.toRequest();
                var responses = getStudentResultsUsecase.execute(request);
                return ResponseEntity.ok(
                                ResponseDto.of(responses, "OK", "Student results retrieved successfully"));
        }

        @GetMapping("/my-results/{resultId}")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> getMyResultDetail(
                        @PathVariable("resultId") @Positive Long resultId) {
                GetMyResultDetailRequestDto requestDto = GetMyResultDetailRequestDto.builder()
                                .resultId(resultId)
                                .build();
                GetExamResultDetailResponse response = getMyResultDetailUsecase.execute(requestDto.toRequest());
                return ResponseEntity.ok(
                                ResponseDto.of(GetExamResultDetailResponseDto.fromResponse(response), "OK",
                                                "Result detail retrieved successfully"));
        }

        @GetMapping("/my-results/{resultId}/feedback")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> getMyResultFeedback(
                        @PathVariable("resultId") @Positive Long resultId) {
                GetStudentFeedbackResponse response = getStudentFeedbackUsecase.execute(resultId);
                return ResponseEntity.ok(
                                ResponseDto.of(GetStudentFeedbackResponseDto.fromResponse(response), "OK",
                                                "Student feedback retrieved successfully"));
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

        @PostMapping("/{examId}/clear-schema")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> clearSchema(
                        @PathVariable("examId") @Positive Long examId,
                        HttpServletRequest httpRequest) {
                String ip = getClientIp(httpRequest);
                String ua = httpRequest.getHeader("User-Agent");
                clearExamSchemaUsecase.execute(new ClearExamSchemaRequest(examId, ip, ua));
                return ResponseEntity.ok(
                                ResponseDto.of(null, "OK", "Đã xoá sạch dữ liệu schema thành công"));
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
                                                "Nộp bài thành công! Đang chấm điểm."));
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

        @PostMapping("/{examId}/heartbeat")
        @PreAuthorize("hasRole('STUDENT')")
        public ResponseEntity<ResponseDto> heartbeat(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid RecordHeartbeatRequestDto requestDto) {
                recordHeartbeatUsecase.execute(requestDto.toRequest(examId));
                return ResponseEntity.ok(ResponseDto.of(
                                new HeartbeatResponseDto(true, System.currentTimeMillis()),
                                "OK",
                                "Heartbeat recorded"));
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
                // Priority 1: X-Forwarded-For (standard for proxies)
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                        // The first IP in the list is the original client IP
                        return xForwardedFor.split(",")[0].trim();
                }

                // Priority 2: X-Real-IP (fallback for some Nginx configs)
                String xRealIp = request.getHeader("X-Real-IP");
                if (xRealIp != null && !xRealIp.isBlank()) {
                        return xRealIp;
                }

                // Priority 3: Last resort (will be Docker Gateway if headers are missing)
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
                                ResponseDto.of(rubricJson, "OK", "Tạo rubric thành công!"));
        }

        // ===== TEST GRADING =====

        @PostMapping("/{examId}/test-grade-insert")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> testGradeInsert(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid TestGradeInsertRequestDto requestDto) {
                RubricTestGradeResponse result = rubricTestingUsecase.testGradeInsert(
                                requestDto.toRequest(examId, objectMapper));
                return ResponseEntity.ok(ResponseDto.of(result, "OK", "Test grading completed"));
        }

        @PostMapping("/{examId}/test-grade-select")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> testGradeSelect(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid TestGradeSelectRequestDto requestDto) {
                RubricTestGradeResponse result = rubricTestingUsecase.testGradeSelect(
                                requestDto.toRequest(examId, objectMapper));
                return ResponseEntity.ok(ResponseDto.of(result, "OK", "Test grading completed"));
        }

        @PostMapping("/{examId}/test-grade-routine")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> testGradeRoutine(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid TestGradeRoutineRequestDto requestDto) {
                RubricTestGradeResponse result = rubricTestingUsecase.testGradeRoutine(
                                requestDto.toRequest(examId, objectMapper));
                return ResponseEntity.ok(ResponseDto.of(result, "OK", "Routine test grading completed"));
        }

        @PostMapping("/{examId}/test-grade-trigger")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> testGradeTrigger(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid TestGradeTriggerRequestDto requestDto) {
                RubricTestGradeResponse result = rubricTestingUsecase.testGradeTrigger(
                                requestDto.toRequest(examId, objectMapper));
                return ResponseEntity.ok(ResponseDto.of(result, "OK", "Trigger test grading completed"));
        }

        @PostMapping("/{examId}/run-select-testcase")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> runSelectTestcase(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid ExecuteSelectQueryRequestDto requestDto) {
                ExecuteSelectTestCaseResponse result = rubricTestingUsecase
                                .executeSelectTestCase(requestDto.toRequest(examId));
                return ResponseEntity.ok(ResponseDto.of(
                                ExecuteSelectTestCaseResponseDto.fromResponse(result),
                                "OK",
                                "Run testcase completed"));
        }

        @PostMapping("/{examId}/build-insert-tables")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> buildInsertTables(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid BuildInsertTablesRequestDto requestDto) {
                BuildInsertTablesResponse result = rubricTestingUsecase.buildInsertTablesFromAnswer(
                                examId,
                                requestDto.correctQuery());
                return ResponseEntity.ok(ResponseDto.of(
                                BuildInsertTablesResponseDto.fromResponse(result),
                                "OK",
                                "Build insert tables completed"));
        }

        @PostMapping("/{examId}/build-create-tables")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> buildCreateTables(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid BuildCreateTablesRequestDto requestDto) {
                BuildCreateTablesResponse result = rubricTestingUsecase.buildCreateTablesFromAnswer(
                                examId,
                                requestDto.correctQuery());
                return ResponseEntity.ok(ResponseDto.of(
                                BuildCreateTablesResponseDto.fromResponse(result),
                                "OK",
                                "Build create tables completed"));
        }

        @PostMapping("/test-grade")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> testGrade(
                        @RequestBody @Valid TestGradeCreateTableRequestDto requestDto) {
                RubricTestGradeResponse result = rubricTestingUsecase.testGradeCreateTable(
                                requestDto.toRequest(objectMapper));
                return ResponseEntity.ok(ResponseDto.of(result, "OK", "Test grading completed"));
        }

        // ===== WHITEBOX (method-based grading) =====

        @GetMapping("/whitebox/catalog")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getWhiteboxCatalog(
                        @RequestParam(value = "questionType", required = false) String questionType) {
                return ResponseEntity.ok(ResponseDto.of(
                                whiteboxCatalogUsecase.execute(questionType), "OK", "Whitebox catalog"));
        }

        @PostMapping("/whitebox/validate")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> validateWhitebox(
                        @RequestBody @Valid WhiteboxValidateRequestDto requestDto) {
                return ResponseEntity.ok(ResponseDto.of(
                                whiteboxValidateUsecase.execute(requestDto.toRequest()),
                                "OK", "Whitebox validation completed"));
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
                                                "Lưu nháp thành công!"));
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

        @PostMapping(value = "/{examId}/extract-questions-from-pdf", consumes = MediaType.APPLICATION_JSON_VALUE)
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> extractQuestionsFromPdf(
                        @PathVariable("examId") @Positive Long examId) {
                ExtractQuestionsFromPdfRequest request = new ExtractQuestionsFromPdfRequest(examId);
                ExtractQuestionsFromPdfResponse response = extractQuestionsFromPdfUsecase.execute(request);
                return ResponseEntity.ok(ResponseDto.of(
                                ExtractQuestionsFromPdfResponseDto.fromResponse(response),
                                "OK",
                                "Questions extracted successfully"));
        }

        @PostMapping("/{examId}/students/{studentId}/remind")
        public ResponseEntity<ResponseDto> remindStudent(
                        @PathVariable Long examId,
                        @PathVariable Long studentId,
                        @RequestBody Map<String, String> request) {
                String message = request.get("message");
                remindStudentUsecase.execute(examId, studentId, message);
                return ResponseEntity.ok(ResponseDto.of(null, "SUCCESS", "Đã gửi nhắc nhở thành công"));
        }

        @PostMapping("/{examId}/students/{studentId}/force-submit")
        public ResponseEntity<ResponseDto> forceSubmitExam(
                        @PathVariable Long examId,
                        @PathVariable Long studentId) {
                forceSubmitExamUsecase.execute(examId, studentId);
                return ResponseEntity.ok(ResponseDto.of(null, "SUCCESS", "Đã cưỡng chế nộp bài thành công"));
        }

        @PostMapping("/{examId}/export-pdf")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<byte[]> exportExamPdf(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody(required = false) @Valid ExportExamPdfRequestDto body) {
                ExportExamPdfRequest request = body != null
                                ? body.toRequest(examId)
                                : new ExportExamPdfRequest(examId, null);
                ExportExamPdfResponse response = exportExamPdfUsecase.execute(request);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_PDF);
                headers.set(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + response.fileName() + "\"");

                return ResponseEntity.ok()
                                .headers(headers)
                                .body(response.content());
        }

        // ===== TEACHER REVIEW SCHEMA ENDPOINTS =====

        @PostMapping("/{examId}/results/{resultId}/execute-sql")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> teacherExecuteSqlOnResult(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("resultId") @Positive Long resultId,
                        @RequestBody @Valid TeacherExecuteSqlOnResultRequestDto requestDto) {
                ExecuteSqlResponse response = teacherExecuteSqlOnResultUsecase.execute(
                                requestDto.toRequest(examId, resultId));
                return ResponseEntity.ok(ResponseDto.of(
                                ExecuteSqlResponseDto.fromResponse(response), "OK", "SQL executed"));
        }

        @PostMapping("/{examId}/results/{resultId}/reset-schema")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> teacherResetResultSchema(
                        @PathVariable("examId") @Positive Long examId,
                        @PathVariable("resultId") @Positive Long resultId) {
                teacherResetResultSchemaUsecase.execute(new TeacherResetResultSchemaRequest(examId, resultId));
                return ResponseEntity.ok(ResponseDto.of(null, "OK", "Schema đã được reset thành công"));
        }

        @DeleteMapping("/{examId}/schemas")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> dropAllExamSchemas(
                        @PathVariable("examId") @Positive Long examId) {
                dropAllExamSchemasUsecase.execute(new DropAllExamSchemasRequest(examId));
                return ResponseEntity.ok(ResponseDto.of(null, "OK", "Đã xóa toàn bộ schema của bài thi"));
        }

        // ===== PREVIEW ENDPOINTS =====

        @GetMapping("/{examId}/preview")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getExamPreview(
                        @PathVariable("examId") @Positive Long examId) {
                GetStudentExamResponse response = getExamPreviewUsecase.execute(examId);
                return ResponseEntity.ok(
                                ResponseDto.of(GetStudentExamResponseDto.fromResponse(response), "OK",
                                                "Preview exam retrieved successfully"));
        }

        @PostMapping("/{examId}/preview/initialize")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> initializePreviewSchema(
                        @PathVariable("examId") @Positive Long examId) {
                InitializePreviewSchemaResponse response = initializePreviewSchemaUsecase.execute(examId);
                return ResponseEntity.ok(
                                ResponseDto.of(InitializePreviewSchemaResponseDto.fromResponse(response), "OK",
                                                "Preview schema initialized"));
        }

        @PostMapping("/{examId}/preview/clear-schema")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> clearPreviewSchema(
                        @PathVariable("examId") @Positive Long examId) {
                clearPreviewSchemaUsecase.execute(examId);
                return ResponseEntity.ok(ResponseDto.of(null, "OK", "Preview schema cleared"));
        }

        @PostMapping("/{examId}/preview/submit")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> previewSubmitExam(
                        @PathVariable("examId") @Positive Long examId,
                        @RequestBody @Valid PreviewSubmitRequestDto requestDto) {
                SubmitExamResponse response = previewSubmitExamUsecase.execute(requestDto.toRequest(examId));
                return ResponseEntity.ok(
                                ResponseDto.of(SubmitExamResponseDto.fromResponse(response), "OK",
                                                "Preview graded successfully"));
        }
}
