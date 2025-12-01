package graduation_project_be.user.infrastructure.persistence;

import graduation_project_be.user.domain.models.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Table(name = "users")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String email;

    private String password;

    public User toModel() {
        return User.builder()
                .id(id)
                .email(email)
                .password(password)
                .build();
    }

    public static UserEntity fromModel(User userModel) {
        return UserEntity.builder()
                .id(userModel.getId())
                .email(userModel.getEmail())
                .password(userModel.getPassword())
                .build();
    }
}
