package org.yan.lightofshadowbackend.auth.dto;

/**
 * @param target 收件邮箱（优先）
 * @param account 兼容客户端既有字段，等价于 target
 */
public record SendCodeRequest(String channel, String target, String account, String scene) {

    public String effectiveTarget() {
        return target != null && !target.isBlank() ? target : account;
    }
}
