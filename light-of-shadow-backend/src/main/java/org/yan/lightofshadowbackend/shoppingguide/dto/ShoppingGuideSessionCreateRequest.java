package org.yan.lightofshadowbackend.shoppingguide.dto;

public record ShoppingGuideSessionCreateRequest(
        String entry,
        String source,
        String locale) {
}

