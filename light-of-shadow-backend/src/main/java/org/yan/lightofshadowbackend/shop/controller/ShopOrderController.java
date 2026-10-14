package org.yan.lightofshadowbackend.shop.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yan.lightofshadowbackend.common.ApiResponse;
import org.yan.lightofshadowbackend.shop.dto.CheckoutOrderRequest;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;
import org.yan.lightofshadowbackend.shop.service.ShopCommerceService;

@RestController
@RequestMapping("/api/shop/orders")
public class ShopOrderController {

    private final ShopAuthSupport authSupport;
    private final ShopCommerceService shopCommerceService;

    public ShopOrderController(ShopAuthSupport authSupport, ShopCommerceService shopCommerceService) {
        this.authSupport = authSupport;
        this.shopCommerceService = shopCommerceService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> checkout(@RequestBody(required = false) CheckoutOrderRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        var result = shopCommerceService.checkout(userId, request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", result.orderId());
        data.put("orderNo", result.orderNo());
        data.put("status", result.status());
        data.put("totalCent", result.totalCent());
        data.put("createdAt", result.createdAt());
        return ApiResponse.success(data);
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        var result = shopCommerceService.listOrders(userId, page, size, status);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.page());
        data.put("size", result.size());
        data.put("total", result.total());
        data.put("items", result.items());
        return ApiResponse.success(data);
    }
}

