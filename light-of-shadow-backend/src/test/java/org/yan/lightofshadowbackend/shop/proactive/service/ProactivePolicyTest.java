package org.yan.lightofshadowbackend.shop.proactive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactiveEventEntity;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactivePrefEntity;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactiveEventMapper;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactivePrefMapper;

/**
 * 策略层规则单测：每条规则一组用例，含边界（22:00 整点、第 2 次 dismiss、DUP_ORDER 豁免）。
 */
@ExtendWith(MockitoExtension.class)
class ProactivePolicyTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Mock
    private ProactiveEventMapper eventMapper;

    @Mock
    private ProactivePrefMapper prefMapper;

    private ProactivePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ProactivePolicy(eventMapper, prefMapper);
        // 默认：开关全开、无历史事件、当日 0 条
        lenient().when(prefMapper.selectByUserIdAndType(anyString(), anyString())).thenReturn(null);
        lenient().when(eventMapper.selectLatestByUserTypeRef(anyString(), anyString(), anyString())).thenReturn(null);
        lenient().when(eventMapper.countSentToday(anyString(), anyString(), any())).thenReturn(0L);
        lenient().when(eventMapper.selectRecentByUserAndType(anyString(), anyString(), anyInt())).thenReturn(new ArrayList<>());
    }

    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(LocalDate.of(2026, 6, 1), LocalTime.of(hour, minute), ZONE).toInstant();
    }

    @Test
    void 总开关关闭_丢弃() {
        when(prefMapper.selectByUserIdAndType("u1", "ALL")).thenReturn(pref("u1", "ALL", 0));
        ProactivePolicy.Decision d = policy.evaluate("u1", "UNPAID_ORDER", "o1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.DROP, d.verdict());
    }

    @Test
    void 分类开关关闭_丢弃() {
        when(prefMapper.selectByUserIdAndType("u1", "CART_IDLE")).thenReturn(pref("u1", "CART_IDLE", 0));
        ProactivePolicy.Decision d = policy.evaluate("u1", "CART_IDLE", "p1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.DROP, d.verdict());
    }

    @Test
    void 免打扰22点整_顺延到次日8点() {
        ProactivePolicy.Decision d = policy.evaluate("u1", "UNPAID_ORDER", "o1", at(22, 0));
        assertEquals(ProactivePolicy.Verdict.DEFER, d.verdict());
        ZonedDateTime until = d.deferUntil().atZone(ZONE);
        assertEquals(LocalTime.of(8, 0), until.toLocalTime());
        assertEquals(2, until.getDayOfMonth()); // 次日
    }

    @Test
    void 免打扰凌晨3点_顺延到当天8点() {
        ProactivePolicy.Decision d = policy.evaluate("u1", "CART_IDLE", "p1", at(3, 0));
        assertEquals(ProactivePolicy.Verdict.DEFER, d.verdict());
        assertEquals(1, d.deferUntil().atZone(ZONE).getDayOfMonth());
    }

    @Test
    void 免打扰DUP_ORDER例外_直接放行() {
        ProactivePolicy.Decision d = policy.evaluate("u1", "DUP_ORDER", "o1", at(23, 30));
        assertEquals(ProactivePolicy.Verdict.ALLOW, d.verdict());
    }

    @Test
    void 工作时段_放行() {
        ProactivePolicy.Decision d = policy.evaluate("u1", "UNPAID_ORDER", "o1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.ALLOW, d.verdict());
    }

    @Test
    void 冷却期内_丢弃() {
        // CART_IDLE 冷却 7 天：3 天前刚发过
        ProactiveEventEntity last = event("CART_IDLE", "PENDING", Instant.parse("2026-05-29T10:00:00Z"));
        when(eventMapper.selectLatestByUserTypeRef("u1", "CART_IDLE", "p1")).thenReturn(last);
        ProactivePolicy.Decision d = policy.evaluate("u1", "CART_IDLE", "p1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.DROP, d.verdict());
    }

    @Test
    void 冷却期满_放行() {
        // CART_IDLE 冷却 7 天：8 天前发过
        ProactiveEventEntity last = event("CART_IDLE", "PENDING", Instant.parse("2026-05-24T10:00:00Z"));
        when(eventMapper.selectLatestByUserTypeRef("u1", "CART_IDLE", "p1")).thenReturn(last);
        ProactivePolicy.Decision d = policy.evaluate("u1", "CART_IDLE", "p1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.ALLOW, d.verdict());
    }

    @Test
    void UNPAID_ORDER无冷却_同单重复由dedupe兜底() {
        // UNPAID 无冷却，策略层不查最近事件（同单重复由 dedupe_key 幂等兜底）
        ProactivePolicy.Decision d = policy.evaluate("u1", "UNPAID_ORDER", "o1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.ALLOW, d.verdict());
    }

    @Test
    void 超出每日限额_顺延次日() {
        when(eventMapper.countSentToday(eq("u1"), eq("DUP_ORDER"), any())).thenReturn(3L);
        ProactivePolicy.Decision d = policy.evaluate("u1", "CART_IDLE", "p1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.DEFER, d.verdict());
        assertTrue(d.deferUntil().isAfter(at(10, 0)));
    }

    @Test
    void DUP_ORDER不占额度_限额满仍放行() {
        // DUP_ORDER 不走每日限额判定，无需 stub 计数
        ProactivePolicy.Decision d = policy.evaluate("u1", "DUP_ORDER", "o1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.ALLOW, d.verdict());
    }

    @Test
    void 连续2次dismiss_负反馈暂停30天() {
        ProactiveEventEntity d1 = event("PRICE_DROP", "DISMISSED", Instant.parse("2026-05-20T10:00:00Z"));
        d1.setResolvedAt(Instant.parse("2026-05-20T10:01:00Z"));
        ProactiveEventEntity d2 = event("PRICE_DROP", "DISMISSED", Instant.parse("2026-05-25T10:00:00Z"));
        d2.setResolvedAt(Instant.parse("2026-05-25T10:01:00Z"));
        when(eventMapper.selectRecentByUserAndType("u1", "PRICE_DROP", 2)).thenReturn(List.of(d2, d1));
        ProactivePolicy.Decision d = policy.evaluate("u1", "PRICE_DROP", "p1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.DROP, d.verdict());
    }

    @Test
    void dismiss间隔着确认_不触发负反馈() {
        ProactiveEventEntity confirmed = event("PRICE_DROP", "CONFIRMED", Instant.parse("2026-05-25T10:00:00Z"));
        ProactiveEventEntity dismissed = event("PRICE_DROP", "DISMISSED", Instant.parse("2026-05-20T10:00:00Z"));
        when(eventMapper.selectRecentByUserAndType("u1", "PRICE_DROP", 2)).thenReturn(List.of(confirmed, dismissed));
        ProactivePolicy.Decision d = policy.evaluate("u1", "PRICE_DROP", "p1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.ALLOW, d.verdict());
    }

    @Test
    void 负反馈超30天后恢复() {
        ProactiveEventEntity d1 = event("PRICE_DROP", "DISMISSED", Instant.parse("2026-04-01T10:00:00Z"));
        d1.setResolvedAt(Instant.parse("2026-04-01T10:01:00Z"));
        ProactiveEventEntity d2 = event("PRICE_DROP", "DISMISSED", Instant.parse("2026-04-02T10:00:00Z"));
        d2.setResolvedAt(Instant.parse("2026-04-02T10:01:00Z"));
        when(eventMapper.selectRecentByUserAndType("u1", "PRICE_DROP", 2)).thenReturn(List.of(d2, d1));
        ProactivePolicy.Decision d = policy.evaluate("u1", "PRICE_DROP", "p1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.ALLOW, d.verdict());
    }

    @Test
    void 默认无记录视为开启() {
        assertNull(prefMapper.selectByUserIdAndType("u1", "ALL"));
        ProactivePolicy.Decision d = policy.evaluate("u1", "UNPAID_ORDER", "o1", at(10, 0));
        assertEquals(ProactivePolicy.Verdict.ALLOW, d.verdict());
    }

    private static ProactivePrefEntity pref(String userId, String type, int enabled) {
        ProactivePrefEntity p = new ProactivePrefEntity();
        p.setUserId(userId);
        p.setType(type);
        p.setEnabled(enabled);
        return p;
    }

    private static ProactiveEventEntity event(String type, String status, Instant createdAt) {
        ProactiveEventEntity e = new ProactiveEventEntity();
        e.setType(type);
        e.setStatus(status);
        e.setCreatedAt(createdAt);
        return e;
    }
}
