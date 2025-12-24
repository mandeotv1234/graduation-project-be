package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GetClassesRequest;
import graduation_project_be.application.usecases.response.GetClassesResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;
import graduation_project_be.domain.models.Class;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetClassesUsecase {

        private final ClassRepository classRepository;
        private final CurrentUserService currentUserService;

        public PaginationResponse<GetClassesResponse> execute(GetClassesRequest request) {
                Long teacherId = currentUserService.getCurrentUserId();

                PaginationParams paginationParams = request.getPaginationParams();

                PaginatedResult<Class> classes = classRepository.findByTeacherId(
                                teacherId,
                                paginationParams);

                List<GetClassesResponse> classResponses = classes.getData().stream()
                                .map(GetClassesResponse::fromModel)
                                .toList();

                return PaginationResponse.valueOf(
                                classResponses,
                                PaginationResponse.PaginationMeta.valueOf(
                                                paginationParams.getPage(),
                                                paginationParams.getSize(),
                                                classes.getPagination().getTotal()));
        }
}
