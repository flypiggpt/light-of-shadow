package org.yan.lightofshadowbackend.shop.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yan.lightofshadowbackend.common.ApiResponse;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.dto.CreateProductRequest;
import org.yan.lightofshadowbackend.shop.dto.UpdateProductRequest;
import org.yan.lightofshadowbackend.shop.entity.ProductEntity;
import org.yan.lightofshadowbackend.shop.mapper.ProductMapper;

@RestController
@RequestMapping("/api/merchant/products")
public class MerchantProductController {

    private final ProductMapper productMapper;
    private final org.yan.lightofshadowbackend.shop.service.ShopAuthSupport authSupport;

    public MerchantProductController(ProductMapper productMapper, org.yan.lightofshadowbackend.shop.service.ShopAuthSupport authSupport) {
        this.productMapper = productMapper;
        this.authSupport = authSupport;
    }

    @PostMapping
    public ApiResponse<ProductEntity> create(@RequestBody CreateProductRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String merchantId = resolveMerchantId(auth);
        validateCreate(req);

        ProductEntity p = new ProductEntity();
        String id = "p_" + UUID.randomUUID().toString().replaceAll("-", "").substring(0, 10);
        p.setId(id);
        p.setName(req.name());
        p.setDescription(req.description());
        p.setPriceCent(req.priceCent());
        p.setStock(req.stock());
        p.setCoverUrl(req.coverUrl());
        p.setMerchantId(merchantId);
        p.setCreatedAt(Instant.now());

        productMapper.insert(p);

        return ApiResponse.success(p);
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String merchantId = resolveMerchantId(auth);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size, 100), 1);
        int offset = (safePage - 1) * safeSize;

        List<ProductEntity> items = productMapper.selectPageByMerchantId(merchantId, offset, safeSize, keyword);
        long total = productMapper.countByMerchantId(merchantId, keyword);

        Map<String, Object> data = new HashMap<>();
        data.put("page", safePage);
        data.put("size", safeSize);
        data.put("total", total);
        data.put("items", items);
        return ApiResponse.success(data);
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductEntity> detail(@PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String merchantId = resolveMerchantId(auth);
        ProductEntity entity = productMapper.selectByIdAndMerchantId(id, merchantId);
        if (entity == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return ApiResponse.success(entity);
    }

    @PatchMapping("/{id}")
    public ApiResponse<ProductEntity> update(@PathVariable("id") String id,
            @RequestBody UpdateProductRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String merchantId = resolveMerchantId(auth);
        validateUpdate(req);

        ProductEntity patch = new ProductEntity();
        patch.setId(id);
        patch.setMerchantId(merchantId);
        patch.setName(req.name());
        patch.setDescription(req.description());
        patch.setPriceCent(req.priceCent());
        patch.setStock(req.stock());
        patch.setCoverUrl(req.coverUrl());

        int affected = productMapper.updateByIdAndMerchantId(patch);
        if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return ApiResponse.success(productMapper.selectByIdAndMerchantId(id, merchantId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String merchantId = resolveMerchantId(auth);
        int affected = productMapper.deleteByIdAndMerchantId(id, merchantId);
        if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return ApiResponse.success(Map.of("id", id));
    }

    private String resolveMerchantId(String authHeader) {
        return authSupport.resolveMerchantId(authHeader);
    }

    private void validateCreate(CreateProductRequest req) {
        if (req == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "参数错误");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "name required");
        }
        if (req.priceCent() == null || req.priceCent() < 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "priceCent invalid");
        }
        if (req.stock() == null || req.stock() < 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "stock invalid");
        }
        if (req.coverUrl() == null || req.coverUrl().isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "coverUrl required");
        }
        if (!(req.coverUrl().startsWith("/media/") || req.coverUrl().startsWith("http://") || req.coverUrl().startsWith("https://"))) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "coverUrl invalid");
        }
    }

    private void validateUpdate(UpdateProductRequest req) {
        if (req == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "参数错误");
        }
        if (req.name() != null && req.name().isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "name invalid");
        }
        if (req.priceCent() != null && req.priceCent() < 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "priceCent invalid");
        }
        if (req.stock() != null && req.stock() < 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "stock invalid");
        }
        if (req.coverUrl() != null && !req.coverUrl().isBlank()
                && !(req.coverUrl().startsWith("/media/") || req.coverUrl().startsWith("http://") || req.coverUrl().startsWith("https://"))) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "coverUrl invalid");
        }
    }
}



