package org.yan.lightofshadowbackend.shoppingguide.service;

import java.util.List;
import java.util.Map;

import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideCompareRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideFeedbackRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideMessageRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideSessionCreateRequest;

public interface ShoppingGuideService {

    Map<String, Object> createSession(String userId, ShoppingGuideSessionCreateRequest request);

    Map<String, Object> getSession(String userId, String sessionId);

    Map<String, Object> sendMessage(String userId, String sessionId, ShoppingGuideMessageRequest request);

    Map<String, Object> listMessages(String userId, String sessionId, int page, int size);

    Map<String, Object> closeSession(String userId, String sessionId);

    Map<String, Object> listRecommendations(String userId, String sessionId, int page, int size);

    Map<String, Object> compareProducts(String userId, ShoppingGuideCompareRequest request);

    Map<String, Object> submitFeedback(String userId, ShoppingGuideFeedbackRequest request);

    List<String> supportedIntents();
}

