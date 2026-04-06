package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.ExamDraft;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

public record SaveExamDraftResponse(
        Long examId,
        Long studentId,
        LocalDateTime savedAt,
        Map<Long, String> answers
) {
    public static SaveExamDraftResponse fromModel(ExamDraft draft) {
        Map<Long, String> answersMap = draft.getAnswers() == null
                ? Map.of()
                : draft.getAnswers().stream()
                        .collect(Collectors.toMap(ExamDraft.DraftAnswer::getQuestionId, ExamDraft.DraftAnswer::getContent));
        return new SaveExamDraftResponse(
                draft.getExamId(),
                draft.getStudentId(),
                draft.getSavedAt(),
                answersMap
        );
    }
}
