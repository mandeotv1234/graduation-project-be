package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.usecases.request.GetClassDetailRequest;
import graduation_project_be.application.usecases.response.GetClassDetailResponse;
import graduation_project_be.domain.models.Class;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.services.CurrentUserService;

@Service
@RequiredArgsConstructor
public class GetClassDetailUsecase {

    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public GetClassDetailResponse execute(GetClassDetailRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Class clazz = classRepository.findById(request.classId());

        if (!clazz.getTeacherId().equals(currentUserId)) {
            throw new UnauthorizedException("User is not the teacher of this class");
        }

        return GetClassDetailResponse.fromModel(clazz);
    }

}
