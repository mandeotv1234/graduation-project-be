package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.domain.models.Class;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SoftDeleteClassUsecase {

    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public void execute(Long classId) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Class clazz = classRepository.findById(classId);
        if (!clazz.getCreatorId().equals(currentUserId)) {
            throw new UnauthorizedException("Only the class creator can delete this class");
        }
        classRepository.softDelete(classId);
    }
}
