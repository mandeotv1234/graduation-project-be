package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.ClearExamSchemaRequest;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.Optional;

@RequiredArgsConstructor
public class ClearExamSchemaUsecase {

    private static final String STUDENT_SCHEMA_FORMAT = "exam_%d_student_%d";

    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final ExamSessionService examSessionService;

    public void execute(ClearExamSchemaRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Role currentRole = currentUserService.getCurrentUser().getRole();

        if (currentRole != Role.STUDENT) {
            throw new UnauthorizedException("Only students can clear their exam schema");
        }

        Exam exam = examRepository.findByIdAndIsPublished(request.examId(), true)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), currentUserId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        // Validate session fingerprint
        if (!examSessionService.isSessionValid(request.examId(), currentUserId, request.ipAddress(), request.userAgent())) {
            throw new BadRequestException("Session invalid or replaced by another device. Please refresh.");
        }

        validateExamTime(request.examId(), currentUserId, exam);

        String schemaName = String.format(STUDENT_SCHEMA_FORMAT, request.examId(), currentUserId);
        
        boolean keepTables = exam.getSettings() != null && Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl());
        examSchemaService.resetSchema(schemaName, keepTables);
    }

    private void validateExamTime(Long examId, Long studentId, Exam exam) {
        Optional<LocalDateTime> startTimeOpt = examSessionService.getExamStartTime(examId, studentId);

        if (startTimeOpt.isPresent()) {
            LocalDateTime examStartedAt = startTimeOpt.get();
            LocalDateTime examDeadline = examStartedAt.plusMinutes(exam.getDurationMinutes());

            if (exam.getEndTime() != null && exam.getEndTime().isBefore(examDeadline)) {
                examDeadline = exam.getEndTime();
            }

            LocalDateTime now = TimeUtils.now();
            if (now.isAfter(examDeadline)) {
                throw new BadRequestException("Exam time has expired. You can no longer clear SQL schema.");
            }
        }
    }
}
