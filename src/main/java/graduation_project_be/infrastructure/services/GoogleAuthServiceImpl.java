package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.GoogleAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Map;

@Service
@Slf4j
public class GoogleAuthServiceImpl implements GoogleAuthService {

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public GoogleUserInfo verifyGoogleToken(String code, String redirectUri) {
        try {
            String tokenUrl = "https://oauth2.googleapis.com/token";

            MultiValueMap<String, String> tokenRequest = new LinkedMultiValueMap<>();
            tokenRequest.add("client_id", clientId);
            tokenRequest.add("client_secret", clientSecret);
            tokenRequest.add("code", code);
            tokenRequest.add("redirect_uri", redirectUri);
            tokenRequest.add("grant_type", "authorization_code");

            @SuppressWarnings("unchecked")
            Map<String, Object> tokenResponse = restTemplate.postForObject(tokenUrl, tokenRequest, Map.class);

            if (tokenResponse == null || !tokenResponse.containsKey("id_token")) {
                log.error("Failed to obtain ID token from Google. Response: {}", tokenResponse);
                throw new IllegalArgumentException("Failed to obtain ID token from Google");
            }

            String idToken = (String) tokenResponse.get("id_token");
            String accessToken = (String) tokenResponse.get("access_token");

            String userInfoUrl = "https://www.googleapis.com/oauth2/v2/userinfo";
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(accessToken);
            org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(headers);

            org.springframework.http.ResponseEntity<Map> response = restTemplate.exchange(
                    userInfoUrl,
                    org.springframework.http.HttpMethod.GET,
                    entity,
                    Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> userInfo = response.getBody();

            if (userInfo == null || !userInfo.containsKey("email")) {
                throw new IllegalArgumentException("Failed to obtain user info from Google");
            }

            String email = (String) userInfo.get("email");
            String name = (String) userInfo.get("name");

            String subject = extractSubjectFromIdToken(idToken);

            if (email == null || subject == null) {
                log.error("Invalid Google token: missing email or subject. Email: {}, Subject: {}", email, subject);
                throw new IllegalArgumentException("Invalid Google token: missing email or subject");
            }

            return new GoogleUserInfo(email, subject, name != null ? name : "");

        } catch (Exception e) {
            log.error("Error verifying Google token", e);
            throw new RuntimeException("Failed to verify Google token", e);
        }
    }

    private String extractSubjectFromIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length >= 2) {
                String payload = parts[1];
                Base64.Decoder decoder = Base64.getUrlDecoder();
                String decoded = new String(decoder.decode(payload));

                @SuppressWarnings("unchecked")
                Map<String, Object> claims = objectMapper.readValue(decoded, Map.class);

                return (String) claims.get("sub");
            }
        } catch (Exception e) {
            log.error("Error extracting subject from ID token", e);
        }
        return null;
    }
}
