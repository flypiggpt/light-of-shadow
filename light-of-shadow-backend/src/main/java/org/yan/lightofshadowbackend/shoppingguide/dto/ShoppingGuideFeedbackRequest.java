package org.yan.lightofshadowbackend.shoppingguide.dto;

public record ShoppingGuideFeedbackRequest(
        String sessionId,
        String messageId,
        Integer rating,
        String feedback) {
}

