package org.yan.lightofshadowbackend.shoppingguide.dto;

public record ShoppingGuideMessageRequest(
        String message,
        ClientContext clientContext) {

    public record ClientContext(
            String page,
            String selectedTab) {
    }
}

