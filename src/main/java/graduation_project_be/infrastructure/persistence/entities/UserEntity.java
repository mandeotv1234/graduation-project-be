package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    @Column(name = "full_name")
    private String fullName;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public User toModel() {
        return User.builder()
                .id(id)
                .email(email)
                .password(password)
                .fullName(fullName)
                .role(role)
                .isActive(isActive)
                .createdAt(createdAt)
                .build();
    }

    public static UserEntity fromModel(User userModel) {
        return UserEntity.builder()
                .id(userModel.getId())
                .email(userModel.getEmail())
                .password(userModel.getPassword())
                .fullName(userModel.getFullName())
                .role(userModel.getRole())
                .isActive(userModel.getIsActive())
                .createdAt(userModel.getCreatedAt())
                .build();
    }
}
