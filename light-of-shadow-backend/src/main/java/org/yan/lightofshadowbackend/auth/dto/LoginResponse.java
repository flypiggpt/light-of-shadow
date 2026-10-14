package org.yan.lightofshadowbackend.auth.dto;

public record LoginResponse(
        String userId,
        String nickname,
        String avatarUrl,
        String accessToken,
        String refreshToken,
        long expiresIn) {
}

