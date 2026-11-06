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

    record OrderItemLine(String productId, String productName, int unitPriceCent, int quantity) {
    }

    record OrderDetail(String orderId, String orderNo, String status, long totalCent,
                       String addressId, String remark, Instant createdAt,
                       List<OrderItemLine> items) {
    }

    OrderDetail getOrderDetail(String userId, String orderId);

    /** 模拟支付：PENDING_PAYMENT → PAID */
    OrderDetail payOrder(String userId, String orderId);

    /** 取消订单（仅 PENDING_PAYMENT），恢复库存 */
    OrderDetail cancelOrder(String userId, String orderId);

    record AddressLine(String id, String receiverName, String phone, String detail, boolean isDefault) {
    }

    List<AddressLine> listAddresses(String userId);

    AddressLine saveAddress(String userId, String id, String receiverName, String phone, String detail, boolean isDefault);

    void deleteAddress(String userId, String addressId);
}

