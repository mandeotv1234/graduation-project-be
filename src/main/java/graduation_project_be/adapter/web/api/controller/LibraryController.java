package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.*;
import graduation_project_be.adapter.web.api.dtos.response.*;
import graduation_project_be.application.usecases.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
@Validated
public class LibraryController {

    private final ShareExamAsTemplateUsecase shareExamAsTemplateUsecase;
    private final GetExamTemplatesUsecase getExamTemplatesUsecase;
    private final GetExamTemplateVersionsUsecase getExamTemplateVersionsUsecase;
    private final CloneExamTemplateUsecase cloneExamTemplateUsecase;
    private final UpdateExamTemplateVisibilityUsecase updateExamTemplateVisibilityUsecase;
    private final HideExamTemplateLineageUsecase hideExamTemplateLineageUsecase;

    @GetMapping("/exam-templates")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> getExamTemplates() {
        List<ExamTemplateListItemResponseDto> items = getExamTemplatesUsecase.execute()
                .stream()
                .map(ExamTemplateListItemResponseDto::fromResponse)
                .toList();
        return ResponseEntity.ok(ResponseDto.of(items, "OK", "Exam templates retrieved"));
    }

    @GetMapping("/exam-templates/source/{sourceExamId}/versions")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> getExamTemplateVersions(
            @PathVariable("sourceExamId") @Positive Long sourceExamId) {
        List<ExamTemplateVersionResponseDto> items = getExamTemplateVersionsUsecase.execute(sourceExamId).stream()
                .map(ExamTemplateVersionResponseDto::fromResponse)
                .toList();
        return ResponseEntity.ok(ResponseDto.of(items, "OK", "Exam template versions retrieved"));
    }

    @PostMapping("/exam-templates")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> shareExamAsTemplate(
            @RequestBody @Valid ShareExamAsTemplateRequestDto requestDto) {
        ShareExamAsTemplateResponseDto result = ShareExamAsTemplateResponseDto.fromResponse(
                shareExamAsTemplateUsecase.execute(requestDto.examId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDto.of(result, "CREATED", "Exam shared as template"));
    }

    @PostMapping("/exam-templates/{id}/clone")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> cloneExamTemplate(
            @PathVariable("id") @Positive Long id,
            @RequestBody @Valid CloneExamTemplateRequestDto requestDto) {
        CloneExamTemplateResponseDto result = CloneExamTemplateResponseDto.fromResponse(
                cloneExamTemplateUsecase.execute(id, requestDto.classId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDto.of(result, "CREATED", "Exam template cloned successfully"));
    }

    @PatchMapping("/exam-templates/{id}/visibility")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> updateExamTemplateVisibility(
            @PathVariable("id") @Positive Long id,
            @RequestBody @Valid UpdateExamTemplateVisibilityRequestDto requestDto) {
        updateExamTemplateVisibilityUsecase.execute(id, requestDto.isVisible());
        return ResponseEntity.ok(ResponseDto.of(null, "OK", "Exam template visibility updated"));
    }

    @PatchMapping("/exam-templates/source/{sourceExamId}/visibility")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> hideExamTemplateLineage(
            @PathVariable("sourceExamId") @Positive Long sourceExamId,
            @RequestBody @Valid UpdateExamTemplateVisibilityRequestDto requestDto) {
        if (Boolean.TRUE.equals(requestDto.isVisible())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ResponseDto.of(null, "BAD_REQUEST", "Bulk unhide is not supported"));
        }
        hideExamTemplateLineageUsecase.execute(sourceExamId);
        return ResponseEntity.ok(ResponseDto.of(null, "OK", "Exam template lineage hidden"));
    }
}
