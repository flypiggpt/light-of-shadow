package org.yan.lightofshadowbackend.shop.service;

/**
 * 对象存储抽象：预签名上传 / 公开访问 URL / 删除。
 * 当前实现 MinIO（S3 协议），后续可替换为阿里云 OSS 等。
 */
public interface ObjectStorageService {

    /** 生成预签名上传 URL；objectKey 形如 product/{userId}/{uuid}.jpg */
    PresignResult createUploadUrl(String objectKey, String contentType, long maxSizeBytes);

    /** 对象公开访问 URL（公开读桶） */
    String publicUrl(String objectKey);

    /** 删除对象（忽略不存在） */
    void delete(String objectKey);

    record PresignResult(String objectKey, String uploadUrl, String publicUrl, long expiresIn) {
    }
}
