package org.yan.lightofshadowbackend.shoppingguide.dto;

import java.util.List;

public record ShoppingGuideCompareRequest(
        String sessionId,
        List<String> productIds) {
}

