package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.MicrosoftAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Map;

@Service
@Slf4j
public class MicrosoftAuthServiceImpl implements MicrosoftAuthService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${AZURE_AD_CLIENT_ID:}")
    private String expectedClientId;

    @Value("${AZURE_AD_TENANT_ID:}")
    private String expectedTenantId;

    @Override
    public MicrosoftUserInfo verifyMicrosoftToken(String idToken) {
        try {
            // The idToken is a JWT with 3 parts: header.payload.signature
            // We decode the payload to extract user claims
            String[] parts = idToken.split("\\.");
            if (parts.length < 3) {
                throw new IllegalArgumentException("Invalid ID token format: expected 3 parts (header.payload.signature)");
            }

            String payload = parts[1];
            byte[] decodedBytes = Base64.getUrlDecoder().decode(payload);
            String decodedPayload = new String(decodedBytes);

            @SuppressWarnings("unchecked")
            Map<String, Object> claims = objectMapper.readValue(decodedPayload, Map.class);

            // Validate audience (aud) — must match our Azure AD Client ID
            String audience = (String) claims.get("aud");
            if (expectedClientId != null && !expectedClientId.isBlank() && !expectedClientId.equals(audience)) {
                log.error("ID token audience mismatch. Expected: {}, Got: {}", expectedClientId, audience);
                throw new SecurityException("ID token audience mismatch");
            }

            // Validate issuer (iss) — must be from our tenant
            String issuer = (String) claims.get("iss");
            if (expectedTenantId != null && !expectedTenantId.isBlank() && issuer != null) {
                String expectedIssuer = "https://login.microsoftonline.com/" + expectedTenantId + "/v2.0";
                if (!expectedIssuer.equals(issuer)) {
                    log.error("ID token issuer mismatch. Expected: {}, Got: {}", expectedIssuer, issuer);
                    throw new SecurityException("ID token issuer mismatch");
                }
            }

            // Validate expiration (exp)
            Object expObj = claims.get("exp");
            if (expObj != null) {
                long exp = expObj instanceof Number ? ((Number) expObj).longValue() : Long.parseLong(expObj.toString());
                if (exp < System.currentTimeMillis() / 1000) {
                    throw new SecurityException("ID token has expired");
                }
            }

            // Extract email: try "email" claim first, then "preferred_username", then "upn"
            String email = (String) claims.get("email");
            if (email == null || !email.contains("@")) {
                email = (String) claims.get("preferred_username");
            }
            if (email == null || !email.contains("@")) {
                email = (String) claims.get("upn");
            }

            String name = (String) claims.get("name");
            String subject = (String) claims.get("sub");

            if (email == null || subject == null) {
                log.error("Invalid Microsoft ID token: missing email or subject. Claims: {}", claims.keySet());
                throw new IllegalArgumentException("Invalid Microsoft ID token: missing email or subject");
            }

            log.info("Microsoft login: email={}, name={}", email, name);
            return new MicrosoftUserInfo(email, subject, name != null ? name : "");

        } catch (SecurityException e) {
            log.error("Security validation failed for Microsoft token: {}", e.getMessage());
            throw new RuntimeException("Microsoft token security validation failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error verifying Microsoft token", e);
            throw new RuntimeException("Failed to verify Microsoft token", e);
        }
    }
}
