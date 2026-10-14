package org.yan.lightofshadowbackend.shop.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yan.lightofshadowbackend.auth.service.TokenService;
import org.yan.lightofshadowbackend.common.ApiResponse;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.entity.ProductEntity;
import org.yan.lightofshadowbackend.shop.mapper.ProductMapper;

@RestController
@RequestMapping("/api/shop/products")
public class ShopProductController {

    private final ProductMapper productMapper;
    private final TokenService tokenService;

    public ShopProductController(ProductMapper productMapper, TokenService tokenService) {
        this.productMapper = productMapper;
        this.tokenService = tokenService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        requireAuth(auth);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size, 100), 1);
        int offset = (safePage - 1) * safeSize;

        List<ProductEntity> items = productMapper.selectPage(offset, safeSize, keyword);
        long total = productMapper.count(keyword);

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
        requireAuth(auth);
        ProductEntity product = productMapper.selectById(id);
        if (product == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return ApiResponse.success(product);
    }

    private void requireAuth(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "未授权");
        }
        String token = authHeader.substring(7).trim();
        try {
            tokenService.parseUserIdFromAccessToken(token);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "未授权");
        }
    }
}

