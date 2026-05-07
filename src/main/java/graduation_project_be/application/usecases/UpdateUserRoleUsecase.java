package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.usecases.request.UpdateUserRoleRequest;
import graduation_project_be.application.usecases.response.UpdateUserRoleResponse;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpdateUserRoleUsecase {

    private final UserRepository userRepository;

    public UpdateUserRoleResponse execute(UpdateUserRoleRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.userId()));
        user.setRole(request.role());
        User updated = userRepository.save(user);
        return UpdateUserRoleResponse.fromModel(updated);
    }
}
