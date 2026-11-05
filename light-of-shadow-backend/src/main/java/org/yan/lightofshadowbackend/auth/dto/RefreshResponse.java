package org.yan.lightofshadowbackend.auth.dto;

/**
 * 刷新响应：轮换后返回新 accessToken + 新 refreshToken。
 */
public record RefreshResponse(String accessToken, String refreshToken, long expiresIn, long refreshExpiresIn) {
}
