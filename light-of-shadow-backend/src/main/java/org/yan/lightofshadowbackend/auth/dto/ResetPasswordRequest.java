package org.yan.lightofshadowbackend.auth.dto;

public record ResetPasswordRequest(String account, String verificationCode, String newPassword) {
}
