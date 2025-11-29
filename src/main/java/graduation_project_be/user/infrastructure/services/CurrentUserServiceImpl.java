package graduation_project_be.user.infrastructure.services;

import graduation_project_be.user.application.port.CurrentUserService;
import graduation_project_be.user.domain.User;
import graduation_project_be.shared.infrastructure.error.exceptions.UserNotFoundException;
import graduation_project_be.user.infrastructure.persistence.jpa.UserJpaRepository;
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
