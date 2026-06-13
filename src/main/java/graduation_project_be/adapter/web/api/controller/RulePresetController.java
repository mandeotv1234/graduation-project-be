package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.dto.RulePresetDto;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.RulePresetUseCase;
import graduation_project_be.domain.models.QuestionType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/exams/rule-presets")
@RequiredArgsConstructor
public class RulePresetController {

    private final RulePresetUseCase rulePresetUseCase;
    private final CurrentUserService currentUserService;

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> createPreset(@RequestBody @Valid RulePresetDto.CreateRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();
        RulePresetDto.Response response = rulePresetUseCase.createPreset(teacherId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDto.of(response, "CREATED", "Mẫu quy tắc đã được tạo thành công"));
    }

    @GetMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> getPresets(
            @RequestParam("questionType") QuestionType questionType,
            @RequestParam(value = "kind", required = false) String kind) {
        Long teacherId = currentUserService.getCurrentUserId();
        List<RulePresetDto.Response> responses = (kind != null && !kind.isBlank())
                ? rulePresetUseCase.getPresetsByTeacherIdAndQuestionTypeAndKind(teacherId, questionType, kind)
                : rulePresetUseCase.getPresetsByTeacherIdAndQuestionType(teacherId, questionType);
        return ResponseEntity.ok(ResponseDto.of(responses, "OK", "Danh sách mẫu quy tắc"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> deletePreset(@PathVariable("id") Long id) {
        Long teacherId = currentUserService.getCurrentUserId();
        rulePresetUseCase.deletePreset(id, teacherId);
        return ResponseEntity.ok(ResponseDto.of(null, "OK", "Mẫu quy tắc đã được xóa"));
    }
}
