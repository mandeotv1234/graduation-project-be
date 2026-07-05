package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ConflictException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.BanStudentRequest;
import graduation_project_be.domain.models.ClassStudentBan;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BanStudentUsecase {

    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final ClassStudentBanRepository classStudentBanRepository;
    private final CurrentUserService currentUserService;

    public void execute(BanStudentRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Long classId = request.classId();
        Long studentId = request.studentId();

        if (!classRepository.existsTeacherAccess(classId, currentUserId)) {
            throw new UnauthorizedException("User does not have access to this class");
        }

        if (!classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)) {
            throw new BadRequestException("Sinh viên không thuộc lớp này");
        }

        if (studentId.equals(currentUserId)) {
            throw new BadRequestException("Không thể tự cấm bản thân");
        }

        if (classRepository.existsTeacherAccess(classId, studentId)) {
            throw new BadRequestException("Không thể cấm giáo viên");
        }

        classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId)
                .ifPresent(ban -> {
                    throw new ConflictException("ClassStudentBan", "studentId", studentId);
                });

        ClassStudentBan ban = ClassStudentBan.builder()
                .classId(classId)
                .studentId(studentId)
                .reason(normalizeReason(request.reason()))
                .bannedBy(currentUserId)
                .active(true)
                .build();

        classStudentBanRepository.save(ban);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.trim();
    }
}
