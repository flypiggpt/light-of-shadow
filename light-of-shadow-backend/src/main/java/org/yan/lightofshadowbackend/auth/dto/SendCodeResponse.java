package org.yan.lightofshadowbackend.auth.dto;

/**
 * @param verificationCode 仅 dev 模式回显；prod 为 null
 */
public record SendCodeResponse(long expiresIn, long resendAfter, String verificationCode) {
}
