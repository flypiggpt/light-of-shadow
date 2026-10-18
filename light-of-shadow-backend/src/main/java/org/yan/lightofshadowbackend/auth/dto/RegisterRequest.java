package org.yan.lightofshadowbackend.auth.dto;

/**
 * 注册：用户名与邮箱分开填写。
 * 邮箱必须验证码（scene=register）。
 */
public record RegisterRequest(String username, String email, String password, String nickname, String verificationCode) {
}
