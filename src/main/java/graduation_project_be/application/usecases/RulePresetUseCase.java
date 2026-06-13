package graduation_project_be.application.usecases;

import graduation_project_be.application.dto.RulePresetDto;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.RulePreset;
import graduation_project_be.domain.repositories.RulePresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RulePresetUseCase {

    private final RulePresetRepository rulePresetRepository;

    public RulePresetDto.Response createPreset(Long teacherId, RulePresetDto.CreateRequest request) {
        String kind = (request.getKind() != null && !request.getKind().isBlank())
                ? request.getKind()
                : "BLACKBOX";
        RulePreset preset = RulePreset.builder()
                .teacherId(teacherId)
                .name(request.getName())
                .questionType(request.getQuestionType())
                .rulesJson(request.getRulesJson())
                .kind(kind)
                .build();

        RulePreset saved = rulePresetRepository.save(preset);
        return toResponse(saved);
    }

    public List<RulePresetDto.Response> getPresetsByTeacherIdAndQuestionType(Long teacherId, QuestionType questionType) {
        List<RulePreset> presets = rulePresetRepository.findByTeacherIdAndQuestionType(teacherId, questionType);
        return presets.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<RulePresetDto.Response> getPresetsByTeacherIdAndQuestionTypeAndKind(Long teacherId, QuestionType questionType, String kind) {
        List<RulePreset> presets = rulePresetRepository.findByTeacherIdAndQuestionTypeAndKind(teacherId, questionType, kind);
        return presets.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public void deletePreset(Long presetId, Long teacherId) {
        RulePreset preset = rulePresetRepository.findById(presetId)
                .orElseThrow(() -> new RuntimeException("Quy tắc chấm định sẵn không tồn tại"));
        
        if (!preset.getTeacherId().equals(teacherId)) {
            throw new RuntimeException("Bạn không có quyền xóa mẫu quy tắc này");
        }

        rulePresetRepository.deleteById(presetId);
    }

    public RulePresetDto.Response updatePreset(Long presetId, Long teacherId, RulePresetDto.UpdateRequest request) {
        RulePreset preset = rulePresetRepository.findById(presetId)
                .orElseThrow(() -> new RuntimeException("Quy tắc chấm định sẵn không tồn tại"));

        if (!preset.getTeacherId().equals(teacherId)) {
            throw new RuntimeException("Bạn không có quyền sửa mẫu quy tắc này");
        }

        // Update only the editable fields; questionType/kind/teacherId stay fixed for the preset.
        preset.setName(request.getName());
        preset.setRulesJson(request.getRulesJson());

        RulePreset saved = rulePresetRepository.save(preset);
        return toResponse(saved);
    }

    private RulePresetDto.Response toResponse(RulePreset model) {
        return RulePresetDto.Response.builder()
                .id(model.getId())
                .teacherId(model.getTeacherId())
                .name(model.getName())
                .questionType(model.getQuestionType())
                .rulesJson(model.getRulesJson())
                .kind(model.getKind())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();
    }
}
