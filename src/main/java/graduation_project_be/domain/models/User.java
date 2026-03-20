package graduation_project_be.domain.models;

import graduation_project_be.domain.models.enums.Role;
import graduation_project_be.domain.models.enums.Status;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(builderMethodName = "internalBuilder")
public class User {
    private Long id;
    private String email;
    private String password;
    private String fullName;
    private Role role;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private String googleSubject;

    public static User.UserBuilder builder() {
        return internalBuilder();
    }
}
