package org.yan.lightofshadowbackend.shop.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yan.lightofshadowbackend.common.ApiResponse;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.service.ObjectStorageService;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;

/**
 * 商家图片：预签名直传。密钥不下发，App/Web 直传对象存储。
 */
@RestController
@RequestMapping("/api/merchant/images")
public class MerchantImageController {

    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;

    private final ObjectStorageService objectStorageService;
    private final ShopAuthSupport authSupport;

    public MerchantImageController(ObjectStorageService objectStorageService, ShopAuthSupport authSupport) {
        this.objectStorageService = objectStorageService;
        this.authSupport = authSupport;
    }

    /** 申请上传 URL：服务端校验类型并生成 objectKey */
    @PostMapping("/upload-request")
    public ApiResponse<Map<String, Object>> uploadRequest(
            @RequestBody UploadRequestBody body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveMerchantId(auth);
        String contentType = body.contentType();
        if (contentType == null || contentType.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "contentType 不能为空");
        }
        String ext = extOf(contentType);
        String objectKey = "product/" + userId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;

        ObjectStorageService.PresignResult result =
                objectStorageService.createUploadUrl(objectKey, contentType, MAX_UPLOAD_BYTES);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("objectKey", result.objectKey());
        data.put("uploadUrl", result.uploadUrl());
        data.put("publicUrl", result.publicUrl());
        data.put("expiresIn", result.expiresIn());
        data.put("maxSizeBytes", MAX_UPLOAD_BYTES);
        return ApiResponse.success(data);
    }

    private String extOf(String contentType) {
        String ct = contentType.toLowerCase();
        return switch (ct) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new ApiException(ErrorCode.BAD_REQUEST, "仅支持 jpg/png/webp");
        };
    }

    public record UploadRequestBody(String contentType) {
    }
}
