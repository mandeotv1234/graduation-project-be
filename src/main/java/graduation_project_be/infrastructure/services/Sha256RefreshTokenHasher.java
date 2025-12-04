package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.RefreshTokenHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.xml.bind.DatatypeConverter;

public class Sha256RefreshTokenHasher implements RefreshTokenHasher {
    private final String pepper;

    public Sha256RefreshTokenHasher(String pepper) {
        this.pepper = pepper == null ? "" : pepper;
    }

    @Override
    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((token + pepper).getBytes(StandardCharsets.UTF_8));
            return DatatypeConverter.printHexBinary(hashed).toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    public boolean verify(String token, String hash) {
        if (token == null || hash == null) return false;
        return hash.equals(hash(token));
    }
}

