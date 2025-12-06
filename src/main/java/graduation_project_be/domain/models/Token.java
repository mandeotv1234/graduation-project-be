package graduation_project_be.domain.models;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(builderMethodName = "internalBuilder")
public class Token {
    String accessToken;
    String refreshToken;
    LocalDateTime accessTokenExpiresAt;
    LocalDateTime refreshTokenExpiresAt;

    public static Token.TokenBuilder builder() {
        return internalBuilder();
    }

}
