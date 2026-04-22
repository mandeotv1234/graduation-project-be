package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ViolationNotificationService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class RemindStudentUsecase {

    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;
    private final ViolationNotificationService violationNotificationService;

    public void execute(Long examId, Long studentId, String message) {
        User teacher = currentUserService.getCurrentUser();

        // Check if teacher owns or has access to exam
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found"));

        if (!exam.getCreatorId().equals(teacher.getId())) {
            throw new UnauthorizedException("You are not authorized to monitor this exam");
        }

        // Send websocket message to student
        violationNotificationService.notifyStudentRemind(examId, studentId, "REMINDER", message);
        log.info("Teacher {} sent reminder to student {} for exam {}", teacher.getId(), studentId, examId);
    }
}
