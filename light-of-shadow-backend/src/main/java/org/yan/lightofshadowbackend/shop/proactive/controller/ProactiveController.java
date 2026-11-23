package org.yan.lightofshadowbackend.shop.proactive.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yan.lightofshadowbackend.common.ApiResponse;
import org.yan.lightofshadowbackend.shop.proactive.service.ProactiveService;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;

/** 主动提醒对外接口（契约见 docs/api/shoppingguide_api.md 第 12 章）。 */
@RestController
@RequestMapping("/api/shoppingguide/proactive")
public class ProactiveController {

    private final ProactiveService proactiveService;
    private final ShopAuthSupport authSupport;

    public ProactiveController(ProactiveService proactiveService, ShopAuthSupport authSupport) {
        this.proactiveService = proactiveService;
        this.authSupport = authSupport;
    }

    /** 拉取未读提议（客户端启动/回前台时调用）。 */
    @GetMapping("/pending")
    public ApiResponse<Map<String, Object>> pending(
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        List<Map<String, Object>> items = proactiveService.pending(userId, limit);
        return ApiResponse.success(Map.of("items", items));
    }

    /** 确认提议：幂等，重复确认返回同一结果。 */
    @PostMapping("/{signalId}/confirm")
    public ApiResponse<Map<String, Object>> confirm(
            @PathVariable("signalId") String signalId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        String actionId = body == null ? "" : String.valueOf(body.getOrDefault("actionId", ""));
        return ApiResponse.success(proactiveService.confirm(userId, auth, signalId, actionId));
    }

    /** 忽略提议：记负反馈（同类连续 2 次 → 该类停 30 天）。 */
    @PostMapping("/{signalId}/dismiss")
    public ApiResponse<Map<String, Object>> dismiss(
            @PathVariable("signalId") String signalId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        authSupport.resolveUserId(auth); // 登录态校验
        return ApiResponse.success(proactiveService.dismiss(signalId));
    }

    /** 查询提醒开关。 */
    @GetMapping("/prefs")
    public ApiResponse<Map<String, Object>> prefs(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        return ApiResponse.success(proactiveService.prefs(userId));
    }

    /** 修改提醒开关：只传要改的字段。 */
    @PutMapping("/prefs")
    public ApiResponse<Map<String, Object>> updatePrefs(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String userId = authSupport.resolveUserId(auth);
        Boolean enabled = body == null ? null : (Boolean) body.get("enabled");
        @SuppressWarnings("unchecked")
        Map<String, Boolean> types = body == null ? null : (Map<String, Boolean>) body.get("types");
        return ApiResponse.success(proactiveService.updatePrefs(userId, enabled, types));
    }
}
