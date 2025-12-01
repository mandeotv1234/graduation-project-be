package graduation_project_be.shared.infrastructure.service_impl;

import graduation_project_be.auth.application.port.JwtService;
import graduation_project_be.shared.infrastructure.error.exceptions.JwtInvalidException;
import graduation_project_be.user.domain.models.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JwtServiceImpl implements JwtService {
    private final Integer jwtTokenValidity;
    private final Integer refreshTokenValidity;
    private final Key key;
    private final String EMAIL = "email";
    private final String STATUS = "status";
    private final String ROLE = "role";
    public JwtServiceImpl(
        Integer jwtTokenValidity,
        Integer refreshTokenValidity,
        String secretKey
    ) {
        this.jwtTokenValidity = jwtTokenValidity;
        this.refreshTokenValidity = refreshTokenValidity;
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes());
    }

    @Override
    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(EMAIL, user.getEmail());
        return Jwts.builder()
            .setClaims(claims)
            .setIssuedAt(new Date(System.currentTimeMillis()))
            .setExpiration(new Date(System.currentTimeMillis() + jwtTokenValidity))
            .signWith(key)
            .compact();
    }

    @Override
    public String generateRefreshToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(EMAIL, user.getEmail());
        return Jwts.builder()
            .setClaims(claims)
            .setIssuedAt(new Date(System.currentTimeMillis()))
            .setExpiration(new Date(System.currentTimeMillis() + refreshTokenValidity))
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

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .getBody();
    }
}