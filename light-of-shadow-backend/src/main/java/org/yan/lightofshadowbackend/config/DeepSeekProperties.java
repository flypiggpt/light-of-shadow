package org.yan.lightofshadowbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "deepseek")
public record DeepSeekProperties(String apiKey, String model, String baseUrl) {
}

