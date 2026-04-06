package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.SaveExamDraftRequest;
import graduation_project_be.application.usecases.response.SaveExamDraftResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class SaveExamDraftUsecase {

    private final ExamRepository examRepository;
    private final ExamDraftRepository examDraftRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;

    public SaveExamDraftResponse execute(SaveExamDraftRequest request) {
        Long studentId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new BadRequestException("Exam not found"));

        boolean enrolled = classEnrollmentRepository
                .existsByClassIdAndStudentId(exam.getClassId(), studentId);
        if (!enrolled) {
            throw new BadRequestException("Student is not enrolled in this exam's class");
        }

        List<ExamDraft.DraftAnswer> answers = request.answers() == null
                ? List.of()
                : request.answers().stream()
                        .map(a -> ExamDraft.DraftAnswer.builder()
                                .questionId(a.questionId())
                                .content(a.content() == null ? "" : a.content())
                                .build())
                        .toList();

        ExamDraft draft = ExamDraft.builder()
                .examId(request.examId())
                .studentId(studentId)
                .answers(answers)
                .savedAt(LocalDateTime.now())
                .clientTimestamp(request.clientTimestamp())
                .build();

        ExamDraft saved = examDraftRepository.save(draft);
        log.debug("Saved exam draft for student={} exam={} with {} answers",
                studentId, request.examId(), answers.size());

        return SaveExamDraftResponse.fromModel(saved);
    }
}
