package org.yan.lightofshadowbackend.shop.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.dto.CheckoutOrderRequest;
import org.yan.lightofshadowbackend.shop.entity.CartItemEntity;
import org.yan.lightofshadowbackend.shop.entity.OrderStatus;
import org.yan.lightofshadowbackend.shop.entity.ProductEntity;
import org.yan.lightofshadowbackend.shop.entity.ShopOrderEntity;
import org.yan.lightofshadowbackend.shop.entity.ShopOrderItemEntity;
import org.yan.lightofshadowbackend.shop.mapper.CartItemMapper;
import org.yan.lightofshadowbackend.shop.mapper.ProductMapper;
import org.yan.lightofshadowbackend.shop.mapper.ShopOrderItemMapper;
import org.yan.lightofshadowbackend.shop.mapper.ShopOrderMapper;
import org.yan.lightofshadowbackend.shop.service.ShopCommerceService;

@Service
@Transactional
public class ShopCommerceServiceImpl implements ShopCommerceService {

    private final CartItemMapper cartItemMapper;
    private final ProductMapper productMapper;
    private final ShopOrderMapper shopOrderMapper;
    private final ShopOrderItemMapper shopOrderItemMapper;

    public ShopCommerceServiceImpl(CartItemMapper cartItemMapper,
            ProductMapper productMapper,
            ShopOrderMapper shopOrderMapper,
            ShopOrderItemMapper shopOrderItemMapper) {
        this.cartItemMapper = cartItemMapper;
        this.productMapper = productMapper;
        this.shopOrderMapper = shopOrderMapper;
        this.shopOrderItemMapper = shopOrderItemMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public CartSummary getCart(String userId) {
        List<CartItemEntity> items = cartItemMapper.selectByUserId(userId);
        List<CartLine> lines = new ArrayList<>();
        long total = 0L;
        for (CartItemEntity item : items) {
            ProductEntity product = requireProduct(item.getProductId());
            int quantity = safeQuantity(item.getQuantity());
            lines.add(new CartLine(String.valueOf(item.getId()), product.getId(), product.getName(), quantity, requireInt(product.getPriceCent())));
            total += (long) requireInt(product.getPriceCent()) * quantity;
        }
        lines.sort(Comparator.comparing(CartLine::itemId));
        return new CartSummary(lines, total);
    }

    @Override
    public CartLine addCartItem(String userId, String productId, int quantity) {
        validateQuantity(quantity);
        ProductEntity product = requireProduct(productId);
        CartItemEntity existing = cartItemMapper.selectByUserIdAndProductId(userId, productId);
        if (existing == null) {
            CartItemEntity item = new CartItemEntity();
            item.setUserId(userId);
            item.setProductId(productId);
            item.setQuantity(quantity);
            cartItemMapper.insert(item);
            existing = cartItemMapper.selectByUserIdAndProductId(userId, productId);
        } else {
            int nextQuantity = safeQuantity(existing.getQuantity()) + quantity;
            cartItemMapper.updateQuantityByIdAndUserId(existing.getId(), userId, nextQuantity);
            existing.setQuantity(nextQuantity);
        }
        return new CartLine(String.valueOf(existing.getId()), product.getId(), product.getName(), safeQuantity(existing.getQuantity()), requireInt(product.getPriceCent()));
    }

    @Override
    public CartLine updateCartItem(String userId, long itemId, int quantity) {
        validateQuantity(quantity);
        CartItemEntity existing = requireCartItem(itemId, userId);
        ProductEntity product = requireProduct(existing.getProductId());
        cartItemMapper.updateQuantityByIdAndUserId(itemId, userId, quantity);
        existing.setQuantity(quantity);
        return new CartLine(String.valueOf(existing.getId()), product.getId(), product.getName(), quantity, requireInt(product.getPriceCent()));
    }

    @Override
    public void removeCartItem(String userId, long itemId) {
        int affected = cartItemMapper.deleteByIdAndUserId(itemId, userId);
        if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "购物车项不存在");
        }
    }

    @Override
    public CheckoutResult checkout(String userId, CheckoutOrderRequest request) {
        List<CartItemEntity> cartItems = cartItemMapper.selectByUserId(userId);
        if (cartItems.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "购物车为空");
        }

        long totalCent = 0L;
        List<ShopOrderItemEntity> orderItems = new ArrayList<>();
        for (CartItemEntity cartItem : cartItems) {
            ProductEntity product = requireProduct(cartItem.getProductId());
            int quantity = safeQuantity(cartItem.getQuantity());
            int unitPrice = requireInt(product.getPriceCent());
            int affected = productMapper.decreaseStockIfEnough(product.getId(), quantity);
            if (affected == 0) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "库存不足");
            }
            totalCent += (long) unitPrice * quantity;

            ShopOrderItemEntity orderItem = new ShopOrderItemEntity();
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setUnitPriceCent(unitPrice);
            orderItem.setQuantity(quantity);
            orderItems.add(orderItem);
        }

        String orderId = "o_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String orderNo = generateOrderNo();

        ShopOrderEntity order = new ShopOrderEntity();
        order.setId(orderId);
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setAddressId(request == null ? null : request.addressId());
        order.setRemark(request == null ? null : request.remark());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalCent((int) totalCent);
        order.setCreatedAt(Instant.now());
        shopOrderMapper.insert(order);

        for (ShopOrderItemEntity orderItem : orderItems) {
            orderItem.setOrderId(orderId);
            orderItem.setCreatedAt(Instant.now());
            shopOrderItemMapper.insert(orderItem);
        }

        cartItemMapper.deleteByUserId(userId);
        return new CheckoutResult(orderId, orderNo, order.getStatus().name(), totalCent, order.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage listOrders(String userId, int page, int size, String status) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(Math.min(size, 100), 1);
        int offset = (safePage - 1) * safeSize;
        String normalizedStatus = normalizeStatus(status);
        List<ShopOrderEntity> orders = shopOrderMapper.selectPageByUserId(userId, offset, safeSize, normalizedStatus);
        long total = shopOrderMapper.countByUserId(userId, normalizedStatus);
        List<OrderLine> lines = new ArrayList<>();
        for (ShopOrderEntity order : orders) {
            lines.add(new OrderLine(order.getId(), order.getOrderNo(), order.getStatus().name(), requireInt(order.getTotalCent()), order.getCreatedAt()));
        }
        return new OrderPage(safePage, safeSize, total, lines);
    }

    private ProductEntity requireProduct(String productId) {
        ProductEntity product = productMapper.selectById(productId);
        if (product == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    private CartItemEntity requireCartItem(long itemId, String userId) {
        CartItemEntity item = cartItemMapper.selectByIdAndUserId(itemId, userId);
        if (item == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "购物车项不存在");
        }
        return item;
    }

    private void validateQuantity(int quantity) {
        if (quantity <= 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "quantity必须大于0");
        }
    }

    private int safeQuantity(Integer quantity) {
        return quantity == null ? 0 : quantity;
    }

    private int requireInt(Integer value) {
        if (value == null) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "数据异常");
        }
        return value;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        try {
            OrderStatus.valueOf(normalized);
            return normalized;
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "订单状态非法");
        }
    }

    private String generateOrderNo() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }
}

