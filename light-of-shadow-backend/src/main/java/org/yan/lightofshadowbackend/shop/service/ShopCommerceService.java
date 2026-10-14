package org.yan.lightofshadowbackend.shop.service;

import java.time.Instant;
import java.util.List;

import org.yan.lightofshadowbackend.shop.dto.CheckoutOrderRequest;
import org.yan.lightofshadowbackend.shop.entity.CartItemEntity;
import org.yan.lightofshadowbackend.shop.entity.ShopOrderEntity;

public interface ShopCommerceService {

    record CartLine(String itemId, String productId, String productName, int quantity, int unitPriceCent) {
    }

    record CartSummary(List<CartLine> items, long totalCent) {
    }

    record CheckoutResult(String orderId, String orderNo, String status, long totalCent, Instant createdAt) {
    }

    record OrderLine(String orderId, String orderNo, String status, long totalCent, Instant createdAt) {
    }

    record OrderPage(int page, int size, long total, List<OrderLine> items) {
    }

    CartSummary getCart(String userId);

    CartLine addCartItem(String userId, String productId, int quantity);

    CartLine updateCartItem(String userId, long itemId, int quantity);

    void removeCartItem(String userId, long itemId);

    CheckoutResult checkout(String userId, CheckoutOrderRequest request);

    OrderPage listOrders(String userId, int page, int size, String status);
}

