package org.yan.lightofshadowbackend.auth.dto;

public record RefreshResponse(String accessToken, long expiresIn) {
}

