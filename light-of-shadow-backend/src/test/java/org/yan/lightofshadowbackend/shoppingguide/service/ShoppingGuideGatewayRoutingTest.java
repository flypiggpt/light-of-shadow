package org.yan.lightofshadowbackend.shoppingguide.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.config.AgentProperties;
import org.yan.lightofshadowbackend.exception.ApiException;
import org.yan.lightofshadowbackend.shop.mapper.ProductMapper;
import org.yan.lightofshadowbackend.shop.service.ShopAuthSupport;
import org.yan.lightofshadowbackend.shoppingguide.client.AgentGatewayClient;
import org.yan.lightofshadowbackend.shoppingguide.dto.ShoppingGuideMessageRequest;
import org.yan.lightofshadowbackend.shoppingguide.entity.SessionEntity;
import org.yan.lightofshadowbackend.shoppingguide.mapper.ShoppingGuideMapper;
import org.yan.lightofshadowbackend.shoppingguide.service.impl.ShoppingGuideCompareService;
import org.yan.lightofshadowbackend.shoppingguide.service.impl.ShoppingGuideServiceImpl;
import org.yan.lightofshadowbackend.shoppingguide.service.impl.ShoppingGuideSessionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** sendMessage 网关路由：转发成功落库 / 网关不可用直接报错（无本地兜底） / 业务错误不降级。 */
class ShoppingGuideGatewayRoutingTest {

    private ShoppingGuideMapper shoppingGuideMapper;
    private AgentGatewayClient gateway;
    private ShoppingGuideServiceImpl service;

    @BeforeEach
    void setUp() {
        shoppingGuideMapper = Mockito.mock(ShoppingGuideMapper.class);
        gateway = Mockito.mock(AgentGatewayClient.class);

        SessionEntity session = new SessionEntity();
        session.setId("sg_t1");
        session.setUserId("u_1");
        session.setStatus("ACTIVE");
        session.setEntry("shop");
        session.setLocale("zh-CN");
        when(shoppingGuideMapper.selectSessionById("sg_t1")).thenReturn(session);
        when(shoppingGuideMapper.selectMessagesBySession("sg_t1")).thenReturn(List.of());

        service = new ShoppingGuideServiceImpl(
                Mockito.mock(ProductMapper.class),
                shoppingGuideMapper,
                Mockito.mock(ShopAuthSupport.class),
                gateway,
                new ShoppingGuideCompareService(Mockito.mock(ProductMapper.class)),
                new ShoppingGuideSessionStore(shoppingGuideMapper, new ObjectMapper()));
    }

    private ShoppingGuideMessageRequest request(String text) {
        return new ShoppingGuideMessageRequest(text, null);
    }

    @Test
    void forwardSuccess_returnsAgentData_andPersistsBothMessages() {
        Map<String, Object> agentData = Map.of(
                "assistantMessage", Map.of("content", "按你的预算推荐"),
                "intent", Map.of("name", "recommend_product", "confidence", 0.9));
        when(gateway.sendMessage(anyString(), anyString(), anyString(), any())).thenReturn(agentData);

        Map<String, Object> result = service.sendMessage("u_1", "Bearer tok", "sg_t1", request("预算200耳机"));

        assertEquals("按你的预算推荐", ((Map<?, ?>) result.get("assistantMessage")).get("content"));
        // user + assistant 各落库一次
        verify(shoppingGuideMapper, Mockito.times(2)).insertMessage(any());
    }

    @Test
    void gatewayUnavailable_throwsInternalError_withoutFallback() {
        when(gateway.sendMessage(anyString(), anyString(), anyString(), any()))
                .thenThrow(new AgentGatewayClient.AgentUnavailableException("down", null));

        ApiException e = assertThrows(ApiException.class,
                () -> service.sendMessage("u_1", "Bearer tok", "sg_t1", request("你好")));
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, e.getErrorCode());
        assertTrue(e.getMessage().contains("暂不可用"));
        // 网关不可用不再回退本地 LLM，也不落库
        verify(shoppingGuideMapper, never()).insertMessage(any());
    }

    @Test
    void gatewayBusinessError_propagatesWithoutFallback() {
        when(gateway.sendMessage(anyString(), anyString(), anyString(), any()))
                .thenThrow(new ApiException(ErrorCode.SESSION_ENDED, "会话已结束"));

        ApiException e = assertThrows(ApiException.class,
                () -> service.sendMessage("u_1", "Bearer tok", "sg_t1", request("你好")));
        assertEquals(ErrorCode.SESSION_ENDED, e.getErrorCode());
        // 业务错误不落库、不降级
        verify(shoppingGuideMapper, never()).insertMessage(any());
    }
}
