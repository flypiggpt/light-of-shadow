package org.yan.lightofshadowbackend.shop.proactive.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shoppingguide.client.AgentGatewayClient;
import org.yan.lightofshadowbackend.shoppingguide.entity.MessageEntity;
import org.yan.lightofshadowbackend.shoppingguide.entity.SessionEntity;
import org.yan.lightofshadowbackend.shoppingguide.mapper.ShoppingGuideMapper;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactiveEventEntity;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactivePrefEntity;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactiveEventMapper;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactivePrefMapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 主动提议编排：派发（PENDING → SENT）、确认/忽略（SENT → CONFIRMED/DISMISSED）、偏好开关。
 *
 * <p>消息落 sg_message（entry=proactive 会话），主动消息进正式会话历史；
 * 提议快照存 proposal_json，客户端拉取时不再回查 Agent。
 */
@Service
public class ProactiveService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveService.class);

    private static final String[] ALL_TYPES = {
        "UNPAID_ORDER", "DUP_ORDER", "CART_IDLE", "PRICE_DROP",
    };

    private final ProactiveEventMapper eventMapper;
    private final ProactivePrefMapper prefMapper;
    private final ShoppingGuideMapper shoppingGuideMapper;
    private final AgentGatewayClient gateway;
    private final ObjectMapper objectMapper;

    public ProactiveService(ProactiveEventMapper eventMapper,
            ProactivePrefMapper prefMapper,
            ShoppingGuideMapper shoppingGuideMapper,
            AgentGatewayClient gateway,
            ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.prefMapper = prefMapper;
        this.shoppingGuideMapper = shoppingGuideMapper;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /** 派发一轮：PENDING 事件 → Agent 挂起 → 落会话消息 → SENT。返回成功条数。 */
    @Transactional
    public int dispatchPending(Instant now) {
        List<ProactiveEventEntity> pending = eventMapper.selectPending(20);
        int sent = 0;
        for (ProactiveEventEntity event : pending) {
            if (dispatchOne(event, now)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean dispatchOne(ProactiveEventEntity event, Instant now) {
        Map<String, Object> proposal;
        try {
            proposal = gateway.prepareProposal(event.getUserId(), null,
                    event.getId(), event.getType(), readJson(event.getPayloadJson()));
        } catch (AgentGatewayClient.AgentUnavailableException e) {
            // Agent 不可达：事件留 PENDING 下轮重试，宁可不发不能发错
            log.warn("proactive.dispatch_deferred signal={}", event.getId());
            return false;
        }
        String proposalJson = writeJson(Map.of(
                "message", proposal.getOrDefault("message", ""),
                "actions", proposal.getOrDefault("actions", List.of())));

        String sessionId = openSession(event.getUserId());
        writeMessage(sessionId, "assistant", String.valueOf(proposal.getOrDefault("message", "")));
        eventMapper.markSent(event.getId(), proposalJson, sessionId, now);
        log.info("proactive.dispatched signal={} type={} session={}", event.getId(), event.getType(), sessionId);
        return true;
    }

    /** 用户未读提议（客户端轮询 / 本地通知数据源）。 */
    public List<Map<String, Object>> pending(String userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 50));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProactiveEventEntity event : eventMapper.selectSentByUser(userId, safeLimit)) {
            Map<String, Object> proposal = readJson(event.getProposalJson());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("signalId", event.getId());
            item.put("type", event.getType());
            item.put("message", proposal.getOrDefault("message", ""));
            item.put("actions", proposal.getOrDefault("actions", List.of()));
            item.put("createdAt", iso(event.getCreatedAt()));
            item.put("expiresAt", iso(event.getProposedAt() == null ? null
                    : event.getProposedAt().plus(24, ChronoUnit.HOURS)));
            items.add(item);
        }
        return items;
    }

    /** 确认：转发 Agent 执行，落结果消息，事件置 CONFIRMED。幂等：重复确认返回同一结果。 */
    @Transactional
    public Map<String, Object> confirm(String userId, String authHeader, String signalId, String actionId) {
        ProactiveEventEntity event = requireEvent(signalId);
        String status = event.getStatus();
        if ("CONFIRMED".equals(status) && actionId.equals(event.getActionId())) {
            return Map.of("signalId", signalId, "actionId", actionId,
                    "reply", lastAssistantMessage(event.getSessionId()), "executed", true);
        }
        if (!"SENT".equals(status)) {
            throw new ApiException(ErrorCode.PROPOSAL_NOT_FOUND, "提议不存在或已处理");
        }

        Map<String, Object> result;
        try {
            result = gateway.confirmProposal(userId, authHeader, signalId, actionId);
        } catch (AgentGatewayClient.AgentUnavailableException e) {
            // 挂起态在 Agent checkpoint 里，仍可重试；4xx 业务错直接透传
            throw e;
        }
        String reply = String.valueOf(result.getOrDefault("reply", ""));
        writeMessage(event.getSessionId(), "assistant", reply);
        eventMapper.updateStatus(signalId, "CONFIRMED", actionId, Instant.now());
        log.info("proactive.confirmed signal={} action={}", signalId, actionId);
        return Map.of("signalId", signalId, "actionId", actionId, "reply", reply,
                "executed", Boolean.TRUE.equals(result.get("executed")));
    }

    /** 忽略：事件置 DISMISSED 并丢弃 Agent 挂起态（负反馈由策略层按历史聚合计算）。 */
    @Transactional
    public Map<String, Object> dismiss(String signalId) {
        ProactiveEventEntity event = requireEvent(signalId);
        if ("DISMISSED".equals(event.getStatus())) {
            return Map.of("signalId", signalId, "status", "DISMISSED");
        }
        if (!"SENT".equals(event.getStatus())) {
            throw new ApiException(ErrorCode.PROPOSAL_NOT_FOUND, "提议不存在或已处理");
        }
        eventMapper.updateStatus(signalId, "DISMISSED", null, Instant.now());
        gateway.discardProposal(signalId);
        log.info("proactive.dismissed signal={}", signalId);
        return Map.of("signalId", signalId, "status", "DISMISSED");
    }

    /** 读提醒开关：总开关 + 分类开关（无记录视为开）。 */
    public Map<String, Object> prefs(String userId) {
        Map<String, Object> types = new LinkedHashMap<>();
        for (String type : ALL_TYPES) {
            types.put(type, prefEnabled(userId, type));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", prefEnabled(userId, "ALL"));
        out.put("types", types);
        return out;
    }

    /** 改提醒开关：只传要改的字段。 */
    @Transactional
    public Map<String, Object> updatePrefs(String userId, Boolean enabled, Map<String, Boolean> types) {
        Instant now = Instant.now();
        if (enabled != null) {
            savePref(userId, "ALL", enabled, now);
        }
        if (types != null) {
            for (Map.Entry<String, Boolean> entry : types.entrySet()) {
                savePref(userId, entry.getKey(), Boolean.TRUE.equals(entry.getValue()), now);
            }
        }
        return Map.of("saved", true);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private ProactiveEventEntity requireEvent(String signalId) {
        ProactiveEventEntity event = eventMapper.selectById(signalId);
        if (event == null) {
            throw new ApiException(ErrorCode.PROPOSAL_NOT_FOUND, "提议不存在或已处理");
        }
        return event;
    }

    private boolean prefEnabled(String userId, String type) {
        ProactivePrefEntity pref = prefMapper.selectByUserIdAndType(userId, type);
        return pref == null || pref.getEnabled() == null || pref.getEnabled() == 1;
    }

    private void savePref(String userId, String type, boolean enabled, Instant now) {
        ProactivePrefEntity pref = new ProactivePrefEntity();
        pref.setUserId(userId);
        pref.setType(type);
        pref.setEnabled(enabled ? 1 : 0);
        pref.setUpdatedAt(now);
        prefMapper.upsert(pref);
    }

    private String openSession(String userId) {
        Instant now = Instant.now();
        SessionEntity session = new SessionEntity();
        session.setId("sg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        session.setUserId(userId);
        session.setEntry("proactive");
        session.setSource("app");
        session.setLocale("zh-CN");
        session.setStatus("ACTIVE");
        session.setWelcomeMessage("");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        shoppingGuideMapper.insertSession(session);
        return session.getId();
    }

    private void writeMessage(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        MessageEntity message = new MessageEntity();
        message.setId("msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContentType("text");
        message.setContent(content);
        message.setCreatedAt(Instant.now());
        shoppingGuideMapper.insertMessage(message);
    }

    private String lastAssistantMessage(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        List<MessageEntity> messages = shoppingGuideMapper.selectMessagesBySession(sessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                return messages.get(i).getContent();
            }
        }
        return "";
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) { // noqa: BLE001 —— 快照损坏按空处理
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) { // noqa: BLE001 —— 序列化失败留空快照
            return "{}";
        }
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
