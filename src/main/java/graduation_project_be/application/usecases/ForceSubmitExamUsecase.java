package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.ViolationNotificationService;
import graduation_project_be.application.usecases.request.SubmitExamRequest;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamDraft;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class ForceSubmitExamUsecase {

    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;
    private final SubmitExamUsecase submitExamUsecase;
    private final ViolationNotificationService violationNotificationService;
    private final ExamSessionService examSessionService;
    private final ExamDraftRepository examDraftRepository;

    public void execute(Long examId, Long studentId) {
        User teacher = currentUserService.getCurrentUser();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found"));

        if (!exam.getCreatorId().equals(teacher.getId())) {
            throw new UnauthorizedException("You are not authorized to monitor this exam");
        }

        // Check if student is actively taking the exam
        if (examSessionService.getExamStartTime(examId, studentId).isEmpty()) {
            throw new BadRequestException("Sinh viên chưa bắt đầu làm bài hoặc đã nộp bài trước đó.");
        }

        // Auto-submit with currently saved answers -> SubmitExamUsecase saves submissions
        // and enqueues grading. Session cleanup is handled by grading worker.
        Optional<ExamDraft> draftOpt = examDraftRepository.findByExamIdAndStudentId(examId, studentId);
        List<SubmitExamRequest.AnswerItem> answers = draftOpt.map(draft -> 
                draft.getAnswers() == null ? List.<SubmitExamRequest.AnswerItem>of() : 
                draft.getAnswers().stream()
                        .map(da -> new SubmitExamRequest.AnswerItem(da.getQuestionId(), da.getContent() != null ? da.getContent() : ""))
                        .collect(Collectors.toList())
        ).orElse(List.of());

        submitExamUsecase.executeAsSystem(new SubmitExamRequest(examId, answers, null, null), studentId, true);

        // Send websocket message to student to close exam
        violationNotificationService.notifyStudentRemind(examId, studentId, "FORCE_SUBMIT", "Giáo viên đã cưỡng chế nộp bài của bạn.");
        log.info("Teacher {} forced submit exam {} for student {}", teacher.getId(), examId, studentId);
    }
}
