package org.yan.lightofshadowbackend.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(Local local) {

    public record Local(String baseDir, String baseUrl, long maxFileSizeBytes) {
    }
}

