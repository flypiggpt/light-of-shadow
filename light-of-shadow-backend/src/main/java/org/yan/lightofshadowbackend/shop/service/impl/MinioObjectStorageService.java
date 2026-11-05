package org.yan.lightofshadowbackend.shop.service.impl;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.config.ObjectStorageProperties;
import org.yan.lightofshadowbackend.shop.service.ObjectStorageService;

/**
 * MinIO / S3 兼容实现。密钥只在服务端，客户端拿预签名 URL 直传。
 */
@Service
public class MinioObjectStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorageService.class);

    private final ObjectStorageProperties properties;
    private final MinioClient minioClient;

    public MinioObjectStorageService(ObjectStorageProperties properties) {
        this.properties = properties;
        if (!properties.isMinio()) {
            this.minioClient = null;
            log.warn("Object storage not configured (app.storage.object.type=minio). Upload disabled.");
            return;
        }
        ObjectStorageProperties.Minio cfg = properties.minio();
        this.minioClient = MinioClient.builder()
                .endpoint(cfg.endpoint())
                .credentials(cfg.accessKey(), cfg.secretKey())
                .build();
    }

    private ObjectStorageProperties.Minio cfg() {
        if (minioClient == null) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "对象存储未配置");
        }
        return properties.minio();
    }

    @Override
    public PresignResult createUploadUrl(String objectKey, String contentType, long maxSizeBytes) {
        ObjectStorageProperties.Minio cfg = cfg();
        validateKey(objectKey);
        validateContentType(contentType);
        try {
            // 预签名 PUT；大小限制由服务端 complete 阶段与 Nginx/MinIO 配合校验
            String internalUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(cfg.bucket())
                            .object(objectKey)
                            .expiry(cfg.presignTtlSeconds(), TimeUnit.SECONDS)
                            .build());
            // 浏览器须走公网入口；签名含 Host，Nginx 反代时还原 Host 即可通过校验
            String uploadUrl = rewriteToPublicHost(internalUrl);
            String publicUrl = publicUrl(objectKey);
            log.info("presigned upload key={} type={}", objectKey, contentType);
            return new PresignResult(objectKey, uploadUrl, publicUrl, cfg.presignTtlSeconds());
        } catch (Exception e) {
            log.error("presign failed key={}", objectKey, e);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "生成上传凭证失败");
        }
    }

    @Override
    public String publicUrl(String objectKey) {
        ObjectStorageProperties.Minio cfg = cfg();
        String base = cfg.publicBaseUrl() != null && !cfg.publicBaseUrl().isBlank()
                ? cfg.publicBaseUrl().replaceAll("/$", "")
                : cfg.endpoint().replaceAll("/$", "");
        return base + "/" + cfg.bucket() + "/" + objectKey;
    }

    @Override
    public void delete(String objectKey) {
        ObjectStorageProperties.Minio cfg = cfg();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(cfg.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            // 删除失败不阻断主流程，记录即可
            log.warn("delete object failed key={}: {}", objectKey, e.getMessage());
        }
    }

    /** 将预签名 URL 的内网 host 换成浏览器可达入口 */
    private String rewriteToPublicHost(String presignedUrl) {
        String base = properties.minio().publicBaseUrl();
        if (base == null || base.isBlank()) {
            return presignedUrl;
        }
        // 形如 http://127.0.0.1:9000/bucket/key?query → http://public/bucket/key?query
        try {
            java.net.URI uri = java.net.URI.create(presignedUrl);
            java.net.URI pub = java.net.URI.create(base);
            return new java.net.URI(pub.getScheme(), pub.getAuthority(),
                    uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception e) {
            return presignedUrl;
        }
    }

    private void validateKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "objectKey 不能为空");
        }
        if (objectKey.contains("..") || objectKey.startsWith("/")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "objectKey 非法");
        }
    }

    private void validateContentType(String contentType) {
        if (contentType == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "contentType 不能为空");
        }
        String ct = contentType.toLowerCase();
        boolean ok = ct.equals("image/jpeg") || ct.equals("image/png")
                || ct.equals("image/webp") || ct.equals("image/jpg");
        if (!ok) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "仅支持 jpg/png/webp 图片");
        }
    }
}
