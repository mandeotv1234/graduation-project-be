package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.GlobalSearchRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GlobalSearchRequest;
import graduation_project_be.application.usecases.response.GlobalSearchResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GlobalSearchUsecase {

    private final GlobalSearchRepository globalSearchRepository;
    private final CurrentUserService currentUserService;

    public GlobalSearchResponse execute(GlobalSearchRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();
        return globalSearchRepository.searchAll(request.getKeyword(), teacherId);
    }
}
