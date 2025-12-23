package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.JwtService;
import graduation_project_be.domain.models.User;
import graduation_project_be.infrastructure.errors.exceptions.JwtInvalidException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Date;

public class JwtServiceImpl implements JwtService {
    private final Integer jwtTokenValidity;
    private final Integer jwtRefreshTokenValidity;
    private final Key key;
    private final String EMAIL = "email";
    private final String STATUS = "status";
    private final String ROLE = "role";
    private final String UID = "uid";
    private final String TID = "tid";
    public JwtServiceImpl(
        Integer jwtTokenValidity,
        Integer jwtRefreshTokenValidity,
        String secretKey
    ) {
        this.jwtTokenValidity = jwtTokenValidity;
        this.jwtRefreshTokenValidity = jwtRefreshTokenValidity;
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes());
    }

    @Override
    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(EMAIL, user.getEmail());
        claims.put(UID, user.getId());
        claims.put(ROLE, user.getRole());
        Instant now = Instant.now();
        return Jwts.builder()
            .setClaims(claims)
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(now.plusMillis(jwtTokenValidity)))
            .signWith(key)
            .compact();
    }

    @Override
    public String generateRefreshToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(EMAIL, user.getEmail());
        claims.put(UID, user.getId());
        String tokenId = UUID.randomUUID().toString();
        claims.put(TID, tokenId);
        Instant now = Instant.now();
        return Jwts.builder()
            .setClaims(claims)
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(now.plusMillis(jwtRefreshTokenValidity)))
            .signWith(key)
            .compact();
    }

    @Override
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (JwtException e) {
            throw new JwtInvalidException("Validate JWT token failed","Invalid JWT");
        }
    }
    @Override
    public String extractEmail(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.get("email", String.class);
        } catch (JwtException e) {
            throw new JwtInvalidException("Extract email from token failed: ", e.getMessage());
        }
    }
    @Override
    public String extractRole(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.get("role", String.class);
        } catch (JwtException e) {
            throw new JwtInvalidException("Extract role from token failed: ", e.getMessage());
        }
    }

    @Override
    public String extractStatus(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.get("status", String.class);
        } catch (JwtException e) {
            throw new JwtInvalidException("Extract status from token failed: ", e.getMessage());
        }
    }

    @Override
    public List<String> extractPermissions(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.get("permissions", List.class);
        } catch (JwtException e){
            throw new JwtInvalidException("Extract permissions from token failed: ", e.getMessage());
        }
    }

    @Override
    public String extractUserId(String token) {
        try {
            Claims claims = extractAllClaims(token);
            Object uid = claims.get(UID);
            return uid == null ? null : String.valueOf(uid);
        } catch (JwtException e) {
            throw new JwtInvalidException("Extract user id from token failed: ", e.getMessage());
        }
    }

    @Override
    public String extractTokenId(String token) {
        try {
            Claims claims = extractAllClaims(token);
            Object tid = claims.get(TID);
            return tid == null ? null : String.valueOf(tid);
        } catch (JwtException e) {
            throw new JwtInvalidException("Extract token id from token failed: ", e.getMessage());
        }
    }

    @Override
    public int getJwtTokenValiditySeconds() {
        return Math.toIntExact(jwtTokenValidity / 1000L);
    }

    @Override
    public int getJwtRefreshTokenValiditySeconds() {
        return Math.toIntExact(jwtRefreshTokenValidity / 1000L);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .getBody();
    }
}