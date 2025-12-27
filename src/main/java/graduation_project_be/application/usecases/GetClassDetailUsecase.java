package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.usecases.request.GetClassDetailRequest;
import graduation_project_be.application.usecases.response.GetClassDetailResponse;
import graduation_project_be.domain.models.Class;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetClassDetailUsecase {

    private final ClassRepository classRepository;

    public GetClassDetailResponse execute(GetClassDetailRequest request) {
        Class clazz = classRepository.findById(request.classId());
        return GetClassDetailResponse.fromModel(clazz);
    }

}
