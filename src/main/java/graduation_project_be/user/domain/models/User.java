package graduation_project_be.user.domain.models;

import graduation_project_be.shared.domain.models.enums.Status;
import graduation_project_be.user.domain.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(builderMethodName = "internalBuilder")
public class User {
    private Long id;
    private String email;
    private String password;

    public static User.UserBuilder builder() {
        return internalBuilder();
    }
}
