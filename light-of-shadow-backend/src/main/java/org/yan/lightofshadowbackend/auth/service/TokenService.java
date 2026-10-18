package org.yan.lightofshadowbackend.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.yan.lightofshadowbackend.config.AuthProperties;
import org.yan.lightofshadowbackend.entity.UserEntity;

@Component
public class TokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthProperties authProperties;
    private final Key signingKey;

    public TokenService(AuthProperties authProperties) {
        this.authProperties = authProperties;
        this.signingKey = Keys.hmacShaKeyFor(sha256(authProperties.jwt().secret()));
    }

    public String createAccessToken(UserEntity user, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setIssuer(authProperties.jwt().issuer())
                .setSubject(user.getId())
                .setIssuedAt(java.util.Date.from(now))
                .setExpiration(java.util.Date.from(now.plus(ttl)))
                .claim("username", user.getUsername())
                .claim("email", user.getEmail())
                .claim("nickname", user.getNickname())
                .claim("avatarUrl", user.getAvatarUrl() == null ? "" : user.getAvatarUrl())
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashRefreshToken(String refreshToken) {
        return HexFormat.of().formatHex(sha256(refreshToken));
    }

    public String parseUserIdFromAccessToken(String accessToken) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(accessToken)
                .getBody();
        return claims.getSubject();
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash token", exception);
        }
    }
}


