package org.yan.lightofshadowbackend.shop.entity;

import java.time.Instant;

public class ShopOrderItemEntity {

    private Long id;
    private String orderId;
    private String productId;
    private String productName;
    private Integer unitPriceCent;
    private Integer quantity;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Integer getUnitPriceCent() {
        return unitPriceCent;
    }

    public void setUnitPriceCent(Integer unitPriceCent) {
        this.unitPriceCent = unitPriceCent;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

