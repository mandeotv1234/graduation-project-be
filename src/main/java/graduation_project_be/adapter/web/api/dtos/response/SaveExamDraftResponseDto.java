package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.SaveExamDraftResponse;

import java.time.LocalDateTime;
import java.util.Map;

public record SaveExamDraftResponseDto(
        Long examId,
        Long studentId,
        LocalDateTime savedAt,
        Map<Long, String> answers
) {
    public static SaveExamDraftResponseDto fromResponse(SaveExamDraftResponse r) {
        return new SaveExamDraftResponseDto(
                r.examId(),
                r.studentId(),
                r.savedAt(),
                r.answers()
        );
    }
}
