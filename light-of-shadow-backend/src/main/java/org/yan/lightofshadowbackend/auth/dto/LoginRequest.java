package org.yan.lightofshadowbackend.auth.dto;

/**
 * 登录二选一：
 *  1) account(用户名或邮箱) + password
 *  2) account(邮箱) + verificationCode
 */
public record LoginRequest(String account, String password, String verificationCode) {
}
