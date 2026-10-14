package org.yan.lightofshadowbackend.shop.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yan.lightofshadowbackend.common.ApiResponse;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.dto.AddCartItemRequest;
import org.yan.lightofshadowbackend.shop.dto.UpdateCartItemRequest;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;
import org.yan.lightofshadowbackend.shop.service.ShopCommerceService;

@RestController
@RequestMapping("/api/shop/cart")
public class ShopCartController {

    private final ShopAuthSupport authSupport;
    private final ShopCommerceService shopCommerceService;

    public ShopCartController(ShopAuthSupport authSupport, ShopCommerceService shopCommerceService) {
        this.authSupport = authSupport;
        this.shopCommerceService = shopCommerceService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> detail(@RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        var summary = shopCommerceService.getCart(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", summary.items());
        data.put("totalCent", summary.totalCent());
        return ApiResponse.success(data);
    }

    @PostMapping("/items")
    public ApiResponse<Map<String, String>> add(@RequestBody AddCartItemRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (request == null || request.productId() == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "参数错误");
        }
        String userId = authSupport.resolveUserId(auth);
        var line = shopCommerceService.addCartItem(userId, request.productId(), request.quantity() == null ? 0 : request.quantity());
        return ApiResponse.success(Map.of("itemId", line.itemId()));
    }

    @PatchMapping("/items/{itemId}")
    public ApiResponse<Map<String, Object>> update(@PathVariable("itemId") long itemId,
            @RequestBody UpdateCartItemRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (request == null || request.quantity() == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "参数错误");
        }
        String userId = authSupport.resolveUserId(auth);
        var line = shopCommerceService.updateCartItem(userId, itemId, request.quantity());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemId", line.itemId());
        data.put("quantity", line.quantity());
        return ApiResponse.success(data);
    }

    @DeleteMapping("/items/{itemId}")
    public ApiResponse<Map<String, String>> delete(@PathVariable("itemId") long itemId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        shopCommerceService.removeCartItem(userId, itemId);
        return ApiResponse.success(Map.of("itemId", String.valueOf(itemId)));
    }
}


