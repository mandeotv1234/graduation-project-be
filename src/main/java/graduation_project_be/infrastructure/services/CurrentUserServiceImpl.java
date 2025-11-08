package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.domain.models.User;
import graduation_project_be.infrastructure.errors.exceptions.UserNotFoundException;
import graduation_project_be.infrastructure.persistence.repositories.jpa.UserJpaRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class CurrentUserServiceImpl implements CurrentUserService {

    private final UserJpaRepository userJpaRepository;

    @Override
    public User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userJpaRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + email)).toModel();
    }

    @Override
    public Long getCurrentUserId() {
        return getCurrentUser().getId();
    }
}
