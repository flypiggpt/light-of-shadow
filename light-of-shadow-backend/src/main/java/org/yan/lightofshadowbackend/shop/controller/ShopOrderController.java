package org.yan.lightofshadowbackend.shop.controller;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** 订单详情（含条目） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        var d = shopCommerceService.getOrderDetail(userId, id);
        return ApiResponse.success(toDetailMap(d));
    }

    /** 模拟支付 */
    @PostMapping("/{id}/pay")
    public ApiResponse<Map<String, Object>> pay(
            @PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        var d = shopCommerceService.payOrder(userId, id);
        return ApiResponse.success("支付成功", toDetailMap(d));
    }

    /** 取消订单（仅待支付，恢复库存） */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(
            @PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        var d = shopCommerceService.cancelOrder(userId, id);
        return ApiResponse.success("订单已取消", toDetailMap(d));
    }

    /** 删除订单（任意状态；待支付先恢复库存） */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(
            @PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        shopCommerceService.deleteOrder(userId, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", id);
        data.put("deleted", true);
        return ApiResponse.success("订单已删除", data);
    }

    private Map<String, Object> toDetailMap(ShopCommerceService.OrderDetail d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("orderId", d.orderId());
        map.put("orderNo", d.orderNo());
        map.put("status", d.status());
        map.put("totalCent", d.totalCent());
        map.put("addressId", d.addressId());
        map.put("remark", d.remark());
        map.put("createdAt", d.createdAt());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ShopCommerceService.OrderItemLine item : d.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", item.productId());
            m.put("productName", item.productName());
            m.put("unitPriceCent", item.unitPriceCent());
            m.put("quantity", item.quantity());
            items.add(m);
        }
        map.put("items", items);
        return map;
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

