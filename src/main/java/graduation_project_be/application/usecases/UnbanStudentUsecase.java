package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.domain.models.ClassStudentBan;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UnbanStudentUsecase {

    private final ClassRepository classRepository;
    private final ClassStudentBanRepository classStudentBanRepository;
    private final CurrentUserService currentUserService;

    public void execute(Long classId, Long studentId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        if (!classRepository.existsTeacherAccess(classId, currentUserId)) {
            throw new UnauthorizedException("User does not have access to this class");
        }

        ClassStudentBan ban = classStudentBanRepository
                .findActiveByClassIdAndStudentId(classId, studentId)
                .orElseThrow(() -> new ResourceNotFoundException("ClassStudentBan", "studentId", studentId));

        ban.setActive(false);
        classStudentBanRepository.save(ban);
    }
}
