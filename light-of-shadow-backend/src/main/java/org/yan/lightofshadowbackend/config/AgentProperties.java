package org.yan.lightofshadowbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Python Agent 网关连接配置（导购消息转发目标）。 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        String baseUrl,
        long connectTimeoutMs,
        long readTimeoutMs) {

    public AgentProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:8000";
        }
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 2000;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = 35000;
        }
    }
}
