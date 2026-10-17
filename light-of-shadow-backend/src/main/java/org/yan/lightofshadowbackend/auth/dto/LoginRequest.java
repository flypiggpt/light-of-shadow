package org.yan.lightofshadowbackend.auth.dto;

/**
 * 登录：二选一
 *  1) account + password
 *  2) account(邮箱) + verificationCode
 */
public record LoginRequest(String account, String password, String verificationCode) {
}
