package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GetCurrentUserRequest;
import graduation_project_be.application.usecases.response.GetCurrentUserResponse;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GetCurrentUserUsecase {

    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public GetCurrentUserResponse execute(GetCurrentUserRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", currentUserId));

        return GetCurrentUserResponse.fromModel(user);
    }
}
