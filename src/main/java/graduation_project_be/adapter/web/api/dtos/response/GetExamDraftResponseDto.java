package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetExamDraftResponse;

import java.time.LocalDateTime;
import java.util.Map;

public record GetExamDraftResponseDto(
        Long examId,
        Long studentId,
        LocalDateTime savedAt,
        String clientTimestamp,
        Map<Long, String> answers
) {
    public static GetExamDraftResponseDto fromResponse(GetExamDraftResponse r) {
        return new GetExamDraftResponseDto(
                r.examId(),
                r.studentId(),
                r.savedAt(),
                r.clientTimestamp(),
                r.answers()
        );
    }
}
