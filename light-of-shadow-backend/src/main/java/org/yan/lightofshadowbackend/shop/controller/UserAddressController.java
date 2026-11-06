package org.yan.lightofshadowbackend.shop.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yan.lightofshadowbackend.common.ApiResponse;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;
import org.yan.lightofshadowbackend.shop.service.ShopCommerceService;

/** 用户收货地址 */
@RestController
@RequestMapping("/api/shop/addresses")
public class UserAddressController {

    private final ShopCommerceService shopCommerceService;
    private final ShopAuthSupport authSupport;

    public UserAddressController(ShopCommerceService shopCommerceService, ShopAuthSupport authSupport) {
        this.shopCommerceService = shopCommerceService;
        this.authSupport = authSupport;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        List<ShopCommerceService.AddressLine> addresses = shopCommerceService.listAddresses(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ShopCommerceService.AddressLine a : addresses) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("receiverName", a.receiverName());
            m.put("phone", a.phone());
            m.put("detail", a.detail());
            m.put("isDefault", a.isDefault());
            items.add(m);
        }
        return ApiResponse.success(Map.of("items", items));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(
            @RequestBody SaveAddressRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        var saved = shopCommerceService.saveAddress(userId, request.id(), request.receiverName(),
                request.phone(), request.detail(), Boolean.TRUE.equals(request.isDefault()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", saved.id());
        m.put("receiverName", saved.receiverName());
        m.put("phone", saved.phone());
        m.put("detail", saved.detail());
        m.put("isDefault", saved.isDefault());
        return ApiResponse.success("已保存", m);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        shopCommerceService.deleteAddress(userId, id);
        return ApiResponse.success("已删除", null);
    }

    public record SaveAddressRequest(String id, String receiverName, String phone, String detail, Boolean isDefault) {
    }
}
