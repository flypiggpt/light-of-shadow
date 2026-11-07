package org.yan.lightofshadowbackend.shop.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.yan.lightofshadowbackend.shop.mapper.UserAddressMapper;
import org.yan.lightofshadowbackend.shop.entity.UserAddressEntity;
import org.yan.lightofshadowbackend.shop.mapper.ShopOrderMapper;
import org.yan.lightofshadowbackend.shop.service.ShopCommerceService;

@Service
@Transactional
public class ShopCommerceServiceImpl implements ShopCommerceService {

    private final CartItemMapper cartItemMapper;
    private final ProductMapper productMapper;
    private final ShopOrderMapper shopOrderMapper;
    private final ShopOrderItemMapper shopOrderItemMapper;
    private final UserAddressMapper userAddressMapper;

    public ShopCommerceServiceImpl(CartItemMapper cartItemMapper,
            ProductMapper productMapper,
            ShopOrderMapper shopOrderMapper,
            ShopOrderItemMapper shopOrderItemMapper,
            UserAddressMapper userAddressMapper) {
        this.cartItemMapper = cartItemMapper;
        this.productMapper = productMapper;
        this.shopOrderMapper = shopOrderMapper;
        this.shopOrderItemMapper = shopOrderItemMapper;
        this.userAddressMapper = userAddressMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public CartSummary getCart(String userId) {
        List<CartItemEntity> items = cartItemMapper.selectByUserId(userId);
        // 批量查商品，避免 N+1
        List<String> productIds = items.stream().map(CartItemEntity::getProductId).distinct().toList();
        Map<String, ProductEntity> productMap = loadProducts(productIds);
        List<CartLine> lines = new ArrayList<>();
        long total = 0L;
        for (CartItemEntity item : items) {
            ProductEntity product = productMap.get(item.getProductId());
            if (product == null) {
                continue;
            }
            int quantity = safeQuantity(item.getQuantity());
            lines.add(new CartLine(String.valueOf(item.getId()), product.getId(), product.getName(), quantity, requireInt(product.getPriceCent())));
            total += (long) requireInt(product.getPriceCent()) * quantity;
        }
        lines.sort(Comparator.comparing(CartLine::itemId));
        return new CartSummary(lines, total);
    }

    private Map<String, ProductEntity> loadProducts(List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<ProductEntity> products = productMapper.selectByIds(productIds);
        Map<String, ProductEntity> map = new HashMap<>();
        for (ProductEntity p : products) {
            map.put(p.getId(), p);
        }
        return map;
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

        // 批量查商品，避免 N+1
        List<String> productIds = cartItems.stream().map(CartItemEntity::getProductId).distinct().toList();
        Map<String, ProductEntity> productMap = loadProducts(productIds);

        long totalCent = 0L;
        List<ShopOrderItemEntity> orderItems = new ArrayList<>();
        for (CartItemEntity cartItem : cartItems) {
            ProductEntity product = productMap.get(cartItem.getProductId());
            if (product == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "商品不存在");
            }
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

    @Override
    @Transactional(readOnly = true)
    public OrderDetail getOrderDetail(String userId, String orderId) {
        ShopOrderEntity order = requireOrder(userId, orderId);
        return toOrderDetail(order);
    }

    /** 按订单 ID 或订单号查单（用户口播的“订单号”是 order_no），必须属于该用户。 */
    private ShopOrderEntity requireOrder(String userId, String orderIdOrNo) {
        ShopOrderEntity order = shopOrderMapper.selectByIdAndUserId(orderIdOrNo, userId);
        if (order == null) {
            order = shopOrderMapper.selectByOrderNoAndUserId(orderIdOrNo, userId);
        }
        if (order == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    @Override
    @Transactional
    public OrderDetail payOrder(String userId, String orderId) {
        ShopOrderEntity order = shopOrderMapper.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "当前状态不可支付");
        }
        // 模拟支付成功
        shopOrderMapper.updateStatusById(orderId, OrderStatus.PAID.name(), Instant.now());
        return toOrderDetail(order);
    }

    @Override
    @Transactional
    public OrderDetail cancelOrder(String userId, String orderId) {
        ShopOrderEntity order = requireOrder(userId, orderId);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "仅待支付订单可取消");
        }
        // 恢复库存
        List<ShopOrderItemEntity> items = shopOrderItemMapper.selectByOrderId(order.getId());
        for (ShopOrderItemEntity item : items) {
            productMapper.increaseStock(item.getProductId(), item.getQuantity());
        }
        shopOrderMapper.updateStatusById(order.getId(), OrderStatus.CANCELED.name(), Instant.now());
        order.setStatus(OrderStatus.CANCELED);
        return toOrderDetail(order);
    }

    @Override
    @Transactional
    public void deleteOrder(String userId, String orderId) {
        ShopOrderEntity order = requireOrder(userId, orderId);
        // 待支付订单直接删除时先恢复库存（与取消同语义，保证不吞库存）
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            List<ShopOrderItemEntity> items = shopOrderItemMapper.selectByOrderId(order.getId());
            for (ShopOrderItemEntity item : items) {
                productMapper.increaseStock(item.getProductId(), item.getQuantity());
            }
        }
        shopOrderItemMapper.deleteByOrderId(order.getId());
        shopOrderMapper.deleteByIdAndUserId(order.getId(), userId);
    }

    private OrderDetail toOrderDetail(ShopOrderEntity order) {
        List<ShopOrderItemEntity> items = shopOrderItemMapper.selectByOrderId(order.getId());
        List<OrderItemLine> lines = items.stream()
                .map(i -> new OrderItemLine(i.getProductId(), i.getProductName(),
                        requireInt(i.getUnitPriceCent()), safeQuantity(i.getQuantity())))
                .toList();
        return new OrderDetail(order.getId(), order.getOrderNo(),
                order.getStatus() == null ? "" : order.getStatus().name(),
                requireInt(order.getTotalCent()), order.getAddressId(), order.getRemark(),
                order.getCreatedAt(), lines);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AddressLine> listAddresses(String userId) {
        return userAddressMapper.selectByUserId(userId).stream()
                .map(a -> new AddressLine(a.getId(), a.getReceiverName(), a.getPhone(),
                        a.getDetail(), a.getIsDefault() != null && a.getIsDefault() == 1))
                .toList();
    }

    @Override
    @Transactional
    public AddressLine saveAddress(String userId, String id, String receiverName,
                                   String phone, String detail, boolean isDefault) {
        if (receiverName == null || receiverName.isBlank()
                || phone == null || phone.isBlank()
                || detail == null || detail.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "收货人/手机号/地址不能为空");
        }
        Instant now = Instant.now();
        String addrId = (id == null || id.isBlank())
                ? "addr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : id;

        if (isDefault) {
            userAddressMapper.clearDefaultByUserId(userId, now);
        }

        UserAddressEntity entity = new UserAddressEntity();
        entity.setId(addrId);
        entity.setUserId(userId);
        entity.setReceiverName(receiverName.trim());
        entity.setPhone(phone.trim());
        entity.setDetail(detail.trim());
        entity.setIsDefault(isDefault ? 1 : 0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        if (id == null || id.isBlank()) {
            userAddressMapper.insert(entity);
        } else {
            UserAddressEntity existing = userAddressMapper.selectByIdAndUserId(addrId, userId);
            if (existing == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "地址不存在");
            }
            entity.setCreatedAt(existing.getCreatedAt());
            userAddressMapper.updateById(entity);
        }
        return new AddressLine(addrId, entity.getReceiverName(), entity.getPhone(),
                entity.getDetail(), isDefault);
    }

    @Override
    @Transactional
    public void deleteAddress(String userId, String addressId) {
        int affected = userAddressMapper.deleteByIdAndUserId(addressId, userId);
        if (affected == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "地址不存在");
        }
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

