package org.yan.lightofshadowbackend.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置。type=minio 时启用 MinIO 实现。
 */
@ConfigurationProperties(prefix = "app.storage.object")
public record ObjectStorageProperties(
        String type,
        Minio minio
) {

    public record Minio(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            String publicBaseUrl,
            int presignTtlSeconds
    ) {
    }

    public boolean isMinio() {
        return "minio".equalsIgnoreCase(type)
                && minio != null
                && minio.endpoint() != null
                && !minio.endpoint().isBlank();
    }
}
