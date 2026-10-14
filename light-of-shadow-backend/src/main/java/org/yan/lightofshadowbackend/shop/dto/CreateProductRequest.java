package org.yan.lightofshadowbackend.shop.dto;

public record CreateProductRequest(
        String name,
        String description,
        Integer priceCent,
        Integer stock,
        String coverUrl,
        String categoryId
) {
}

