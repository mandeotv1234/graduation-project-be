package graduation_project_be.domain.models;

import graduation_project_be.domain.models.enums.Role;
import graduation_project_be.domain.models.enums.Status;
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
