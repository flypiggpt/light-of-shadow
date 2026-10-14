package org.yan.lightofshadowbackend.shop.entity;

import java.time.Instant;

public class ProductEntity {
    private String id;
    private String name;
    private String description;
    private Integer priceCent;
    private Integer stock;
    private String coverUrl;
    private String merchantId;
    private Instant createdAt;

    // getters/setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getPriceCent() { return priceCent; }
    public void setPriceCent(Integer priceCent) { this.priceCent = priceCent; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

