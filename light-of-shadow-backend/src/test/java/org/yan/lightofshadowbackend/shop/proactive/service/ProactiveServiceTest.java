package org.yan.lightofshadowbackend.shop.proactive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
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

/** ProactiveService：派发、确认幂等、忽略、偏好开关（网关 mock）。 */
class ProactiveServiceTest {

    private ProactiveEventMapper eventMapper;
    private ProactivePrefMapper prefMapper;
    private ShoppingGuideMapper shoppingGuideMapper;
    private AgentGatewayClient gateway;
    private ProactiveService service;

    @BeforeEach
    void setUp() {
        eventMapper = Mockito.mock(ProactiveEventMapper.class);
        prefMapper = Mockito.mock(ProactivePrefMapper.class);
        shoppingGuideMapper = Mockito.mock(ShoppingGuideMapper.class);
        gateway = Mockito.mock(AgentGatewayClient.class);
        service = new ProactiveService(eventMapper, prefMapper, shoppingGuideMapper,
                gateway, new ObjectMapper());
    }

    private ProactiveEventEntity event(String id, String status) {
        ProactiveEventEntity e = new ProactiveEventEntity();
        e.setId(id);
        e.setUserId("u_1");
        e.setType("PRICE_DROP");
        e.setRefId("p_1");
        e.setPayloadJson("{\"productId\":\"p_1\",\"productName\":\"牛奶\",\"newPriceCent\":2400}");
        e.setProposalJson("{\"message\":\"要再买一件吗？\",\"actions\":[{\"actionId\":\"add_cart\"}]}");
        e.setSessionId("sg_x");
        e.setStatus(status);
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Test
    void 派发_pending变sent并落会话消息() {
        ProactiveEventEntity pending = event("pe_1", "PENDING");
        when(eventMapper.selectPending(20)).thenReturn(List.of(pending));
        when(gateway.prepareProposal(eq("u_1"), any(), eq("pe_1"), eq("PRICE_DROP"), any()))
                .thenReturn(Map.of("message", "要再买一件吗？",
                        "actions", List.of(Map.of("actionId", "add_cart")),
                        "threadId", "pe_1"));

        int sent = service.dispatchPending(Instant.now());

        assertEquals(1, sent);
        verify(eventMapper).markSent(eq("pe_1"), anyString(), anyString(), any(Instant.class));
        ArgumentCaptor<SessionEntity> session = ArgumentCaptor.forClass(SessionEntity.class);
        verify(shoppingGuideMapper).insertSession(session.capture());
        assertEquals("proactive", session.getValue().getEntry());
        verify(shoppingGuideMapper).insertMessage(any(MessageEntity.class));
    }

    @Test
    void 派发_Agent不可用_留PENDING重试() {
        ProactiveEventEntity pending = event("pe_2", "PENDING");
        when(eventMapper.selectPending(20)).thenReturn(List.of(pending));
        when(gateway.prepareProposal(any(), any(), any(), any(), any()))
                .thenThrow(new AgentGatewayClient.AgentUnavailableException("down", null));

        int sent = service.dispatchPending(Instant.now());

        assertEquals(0, sent);
        verify(eventMapper, never()).markSent(any(), any(), any(), any());
    }

    @Test
    void 确认_转发并置CONFIRMED() {
        ProactiveEventEntity sent = event("pe_3", "SENT");
        when(eventMapper.selectById("pe_3")).thenReturn(sent);
        when(gateway.confirmProposal("u_1", "Bearer t", "pe_3", "add_cart"))
                .thenReturn(Map.of("reply", "已加入购物车", "executed", true));

        Map<String, Object> result = service.confirm("u_1", "Bearer t", "pe_3", "add_cart");

        assertEquals("已加入购物车", result.get("reply"));
        assertEquals(true, result.get("executed"));
        verify(eventMapper).updateStatus(eq("pe_3"), eq("CONFIRMED"), eq("add_cart"), any(Instant.class));
    }

    @Test
    void 确认_幂等_重复确认不重发() {
        ProactiveEventEntity confirmed = event("pe_4", "CONFIRMED");
        confirmed.setActionId("add_cart");
        when(eventMapper.selectById("pe_4")).thenReturn(confirmed);
        when(shoppingGuideMapper.selectMessagesBySession(any()))
                .thenReturn(List.of(message("assistant", "已加入购物车")));

        Map<String, Object> result = service.confirm("u_1", "Bearer t", "pe_4", "add_cart");

        assertEquals("已加入购物车", result.get("reply"));
        verify(gateway, never()).confirmProposal(any(), any(), any(), any());
    }

    @Test
    void 确认_已忽略_业务错() {
        ProactiveEventEntity dismissed = event("pe_5", "DISMISSED");
        when(eventMapper.selectById("pe_5")).thenReturn(dismissed);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.confirm("u_1", "Bearer t", "pe_5", "add_cart"));
        assertEquals(ErrorCode.PROPOSAL_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void 确认_不存在_业务错() {
        when(eventMapper.selectById("pe_none")).thenReturn(null);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.confirm("u_1", "Bearer t", "pe_none", "add_cart"));
        assertEquals(ErrorCode.PROPOSAL_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void 忽略_置DISMISSED并丢弃挂起() {
        ProactiveEventEntity sent = event("pe_6", "SENT");
        when(eventMapper.selectById("pe_6")).thenReturn(sent);

        Map<String, Object> result = service.dismiss("pe_6");

        assertEquals("DISMISSED", result.get("status"));
        verify(eventMapper).updateStatus(eq("pe_6"), eq("DISMISSED"), eq(null), any(Instant.class));
        verify(gateway).discardProposal("pe_6");
    }

    @Test
    void 忽略_幂等() {
        ProactiveEventEntity dismissed = event("pe_7", "DISMISSED");
        when(eventMapper.selectById("pe_7")).thenReturn(dismissed);

        assertEquals("DISMISSED", service.dismiss("pe_7").get("status"));
        verify(gateway, never()).discardProposal(any());
    }

    @Test
    void 未读提议_返回快照不回查Agent() {
        ProactiveEventEntity sent = event("pe_8", "SENT");
        when(eventMapper.selectSentByUser("u_1", 10)).thenReturn(List.of(sent));

        List<Map<String, Object>> items = service.pending("u_1", 10);

        assertEquals(1, items.size());
        assertEquals("pe_8", items.get(0).get("signalId"));
        assertEquals("要再买一件吗？", items.get(0).get("message"));
        verify(gateway, never()).prepareProposal(any(), any(), any(), any(), any());
    }

    @Test
    void 偏好_默认全开() {
        when(prefMapper.selectByUserIdAndType(any(), any())).thenReturn(null);
        Map<String, Object> prefs = service.prefs("u_1");
        assertEquals(true, prefs.get("enabled"));
        @SuppressWarnings("unchecked")
        Map<String, Boolean> types = (Map<String, Boolean>) prefs.get("types");
        assertTrue(types.values().stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void 偏好_关闭单类() {
        service.updatePrefs("u_1", null, Map.of("PRICE_DROP", false));
        ArgumentCaptor<ProactivePrefEntity> captor = ArgumentCaptor.forClass(ProactivePrefEntity.class);
        verify(prefMapper).upsert(captor.capture());
        assertEquals("PRICE_DROP", captor.getValue().getType());
        assertEquals(0, captor.getValue().getEnabled());
    }

    @Test
    void 偏好_总开关关闭后读取为关() {
        ProactivePrefEntity off = new ProactivePrefEntity();
        off.setUserId("u_1");
        off.setType("ALL");
        off.setEnabled(0);
        when(prefMapper.selectByUserIdAndType("u_1", "ALL")).thenReturn(off);
        assertEquals(false, service.prefs("u_1").get("enabled"));
    }

    private MessageEntity message(String role, String content) {
        MessageEntity m = new MessageEntity();
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
