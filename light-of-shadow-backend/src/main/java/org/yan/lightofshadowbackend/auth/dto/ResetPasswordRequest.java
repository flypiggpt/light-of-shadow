package org.yan.lightofshadowbackend.auth.dto;

/**
 * 找回密码：通过邮箱验证码重置。
 * email 优先；account 为兼容字段，等价于 email。
 */
public record ResetPasswordRequest(String email, String account, String verificationCode, String newPassword) {

    public String effectiveEmail() {
        return email != null && !email.isBlank() ? email : account;
    }
}
