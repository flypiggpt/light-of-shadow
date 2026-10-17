package org.yan.lightofshadowbackend.auth.dto;

public record RegisterRequest(String account, String password, String nickname, String verificationCode) {
}
