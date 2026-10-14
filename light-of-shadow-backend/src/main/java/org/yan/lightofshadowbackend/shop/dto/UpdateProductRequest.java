package org.yan.lightofshadowbackend.shop.dto;

public record UpdateProductRequest(
        String name,
        String description,
        Integer priceCent,
        Integer stock,
        String coverUrl
) {
}

