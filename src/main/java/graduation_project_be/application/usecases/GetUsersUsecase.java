package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.usecases.request.GetUsersRequest;
import graduation_project_be.application.usecases.response.GetUsersResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetUsersUsecase {

    private final UserRepository userRepository;

    public PaginationResponse<GetUsersResponse> execute(GetUsersRequest request) {
        int page = request.page() < 1 ? 0 : request.page() - 1;
        PaginatedResult<User> result = userRepository.findAll(page, request.size());

        List<GetUsersResponse> responses = result.getData().stream()
                .map(GetUsersResponse::fromModel)
                .toList();

        return PaginationResponse.valueOf(
                responses,
                PaginationResponse.PaginationMeta.valueOf(page, request.size(), result.getPagination().getTotal())
        );
    }
}
