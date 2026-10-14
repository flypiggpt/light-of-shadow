package org.yan.lightofshadowbackend.shoppingguide.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yan.lightofshadowbackend.common.ApiResponse;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideCompareRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideFeedbackRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideMessageRequest;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideSessionCreateRequest;
import org.yan.lightofshadowbackend.shoppingguide.service.ShoppingGuideService;

@RestController
@RequestMapping("/api/shoppingguide")
public class ShoppingGuideController {

    private final ShoppingGuideService shoppingGuideService;
    private final ShopAuthSupport authSupport;

    public ShoppingGuideController(ShoppingGuideService shoppingGuideService, ShopAuthSupport authSupport) {
        this.shoppingGuideService = shoppingGuideService;
        this.authSupport = authSupport;
    }

    @PostMapping("/sessions")
    public ApiResponse<Map<String, Object>> createSession(
            @RequestBody(required = false) ShoppingGuideSessionCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        return ApiResponse.success(shoppingGuideService.createSession(userId, request));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> getSession(
            @PathVariable("sessionId") String sessionId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        return ApiResponse.success(shoppingGuideService.getSession(userId, sessionId));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<Map<String, Object>> sendMessage(
            @PathVariable("sessionId") String sessionId,
            @RequestBody ShoppingGuideMessageRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        return ApiResponse.success(shoppingGuideService.sendMessage(userId, sessionId, request));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<Map<String, Object>> listMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        return ApiResponse.success(shoppingGuideService.listMessages(userId, sessionId, page, size));
    }

    @PostMapping("/sessions/{sessionId}/close")
    public ApiResponse<Map<String, Object>> closeSession(
            @PathVariable("sessionId") String sessionId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        return ApiResponse.success(shoppingGuideService.closeSession(userId, sessionId));
    }

    @GetMapping("/sessions/{sessionId}/recommendations")
    public ApiResponse<Map<String, Object>> listRecommendations(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        return ApiResponse.success(shoppingGuideService.listRecommendations(userId, sessionId, page, size));
    }

    @PostMapping("/compare")
    public ApiResponse<Map<String, Object>> compare(
            @RequestBody ShoppingGuideCompareRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        return ApiResponse.success(shoppingGuideService.compareProducts(userId, request));
    }

    @PostMapping("/feedback")
    public ApiResponse<Map<String, Object>> feedback(
            @RequestBody ShoppingGuideFeedbackRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        return ApiResponse.success(shoppingGuideService.submitFeedback(userId, request));
    }
}

