package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.domain.models.Class;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class RemoveTeacherFromClassUsecase {

    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public void execute(Long classId, Long teacherId) {
        Class clazz = classRepository.findById(classId);

        Long currentUserId = currentUserService.getCurrentUserId();
        if (!currentUserId.equals(clazz.getCreatorId())) {
            throw new UnauthorizedException("Only the class creator can remove teachers");
        }

        if (teacherId.equals(clazz.getCreatorId())) {
            throw new BadRequestException("Creator cannot be removed from the class");
        }

        boolean exists = classRepository.existsTeacherAccess(classId, teacherId);
        if (!exists) {
            throw new ResourceNotFoundException("TeacherClass", "classId-teacherId", classId + "-" + teacherId);
        }

        classRepository.deleteTeacherAssociation(classId, teacherId);
    }
}
