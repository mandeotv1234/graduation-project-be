package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.response.ExamTimeResponse;
import graduation_project_be.application.usecases.support.ExamDeadlinePolicy;
import graduation_project_be.application.usecases.support.ExamDeadlinePolicy.ExamDeadlines;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@RequiredArgsConstructor
public class GetExamTimeUsecase {

    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSessionService examSessionService;

    public ExamTimeResponse execute(Long examId) {
        Long studentId = currentUserService.getCurrentUserId();

        // Validate exam exists and is published
        Exam exam = examRepository.findByIdAndIsPublished(examId, true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        // Validate student is enrolled
        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), studentId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        LocalDateTime now = TimeUtils.now();

        // Check if exam hasn't started yet
        if (exam.getStartTime() != null && now.isBefore(exam.getStartTime())) {
            long secondsUntilStart = Duration.between(now, exam.getStartTime()).getSeconds();
            return new ExamTimeResponse(
                    examId, now, exam.getStartTime(), exam.getEndTime(),
                    null, 0, secondsUntilStart,
                    exam.getDurationMinutes(), "WAITING", false);
        }

        // Exam is in time window — check if student has started a session
        Optional<LocalDateTime> studentStartedAt = examSessionService.getExamStartTime(examId, studentId);

        if (studentStartedAt.isEmpty()) {
            if (exam.getEndTime() != null && !now.isBefore(exam.getEndTime())) {
                return new ExamTimeResponse(
                        examId, now, exam.getStartTime(), exam.getEndTime(),
                        null, 0, 0,
                        exam.getDurationMinutes(), "ENDED", true);
            }

            // Student hasn't started yet, but exam is in progress
            return new ExamTimeResponse(
                    examId, now, exam.getStartTime(), exam.getEndTime(),
                    null, 0, 0,
                    exam.getDurationMinutes(), "IN_PROGRESS", false);
        }

        ExamDeadlines deadlines = ExamDeadlinePolicy.calculate(exam, studentStartedAt.get());
        boolean expired = deadlines.isExpired(now);
        boolean inLateSubmissionTime = deadlines.isLateSubmissionTime(now);
        long remainingSeconds = expired
                ? 0
                : ExamDeadlinePolicy.remainingSeconds(now, deadlines.activeDeadline(now));

        return new ExamTimeResponse(
                examId, now, exam.getStartTime(), deadlines.regularDeadline(),
                studentStartedAt.get(),
                remainingSeconds, 0,
                exam.getDurationMinutes(),
                expired ? "ENDED" : inLateSubmissionTime ? "LATE_SUBMISSION" : "IN_PROGRESS",
                expired);
    }
}
