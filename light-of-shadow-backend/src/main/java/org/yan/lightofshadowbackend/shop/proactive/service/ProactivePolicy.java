package org.yan.lightofshadowbackend.shop.proactive.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactiveEventEntity;
import org.yan.lightofshadowbackend.shop.proactive.entity.ProactivePrefEntity;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactiveEventMapper;
import org.yan.lightofshadowbackend.shop.proactive.mapper.ProactivePrefMapper;

/**
 * 策略层：发送前的规则过滤。主动式 Agent 的生死线，全部纯规则，不走 LLM。
 *
 * <p>判定顺序（先便宜的先判）：总开关 → 分类开关 → 免打扰 → 冷却 → 限额 → 负反馈。
 * 输出三种结论：放行 / 顺延（到指定时刻）/ 丢弃。
 */
@Component
public class ProactivePolicy {

    /** 免打扰时段：22:00–08:00（顺延到 08:00 后发，不丢弃）。 */
    static final LocalTime QUIET_START = LocalTime.of(22, 0);
    static final LocalTime QUIET_END = LocalTime.of(8, 0);

    /** 全局限额：每用户每天最多放行条数（DUP_ORDER 不占额度）。 */
    static final int DAILY_LIMIT = 3;

    /** 负反馈：同类最近连续 dismiss 达到该次数 → 停 30 天。 */
    static final int NEGATIVE_FEEDBACK_THRESHOLD = 2;
    static final long NEGATIVE_FEEDBACK_PAUSE_DAYS = 30;

    /** 提议确认时效（小时），超时置 EXPIRED。 */
    static final int PROPOSAL_TTL_HOURS = 24;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ProactiveEventMapper eventMapper;
    private final ProactivePrefMapper prefMapper;

    public ProactivePolicy(ProactiveEventMapper eventMapper, ProactivePrefMapper prefMapper) {
        this.eventMapper = eventMapper;
        this.prefMapper = prefMapper;
    }

    public enum Verdict {
        ALLOW,
        DEFER,
        DROP
    }

    public record Decision(Verdict verdict, Instant deferUntil, String reason) {
        static Decision allow() {
            return new Decision(Verdict.ALLOW, null, "ok");
        }

        static Decision defer(Instant until, String reason) {
            return new Decision(Verdict.DEFER, until, reason);
        }

        static Decision drop(String reason) {
            return new Decision(Verdict.DROP, null, reason);
        }
    }

    /**
     * 对一个待发送信号做放行判定。
     *
     * @param userId 用户
     * @param type   信号类型（UNPAID_ORDER / DUP_ORDER / CART_IDLE / PRICE_DROP）
     * @param refId  关联订单/商品 ID（冷却判定用）
     * @param now    当前时刻（可注入，便于测试）
     */
    public Decision evaluate(String userId, String type, String refId, Instant now) {
        // 1. 总开关
        if (!enabled(userId, "ALL")) {
            return Decision.drop("总开关关闭");
        }
        // 2. 分类开关
        if (!enabled(userId, type)) {
            return Decision.drop("分类开关关闭");
        }
        // 3. 免打扰：22:00–08:00 顺延；DUP_ORDER 例外直接发（守护要抢在支付前）
        if (!"DUP_ORDER".equals(type) && isQuietHours(now)) {
            return Decision.defer(nextQuietEnd(now), "免打扰时段");
        }
        // 4. 冷却：同 ref 的同类信号在冷却期内不重发（UNPAID/DUP 每单一次，靠 dedupe_key 幂等）
        Duration cooldown = cooldownOf(type);
        if (cooldown != null) {
            ProactiveEventEntity last = eventMapper.selectLatestByUserTypeRef(userId, type, refId);
            if (last != null && last.getCreatedAt().plus(cooldown).isAfter(now)) {
                return Decision.drop("冷却中");
            }
        }
        // 5. 全局限额（DUP_ORDER 不占额度）
        if (!"DUP_ORDER".equals(type)) {
            long sentToday = eventMapper.countSentToday(userId, "DUP_ORDER", startOfDay(now));
            if (sentToday >= DAILY_LIMIT) {
                return Decision.defer(startOfDay(now).plus(Duration.ofDays(1)), "超出每日限额");
            }
        }
        // 6. 负反馈：同类最近连续 dismiss 达阈值 → 停 30 天
        List<ProactiveEventEntity> recent = eventMapper.selectRecentByUserAndType(
                userId, type, NEGATIVE_FEEDBACK_THRESHOLD);
        if (dismissStreak(recent) >= NEGATIVE_FEEDBACK_THRESHOLD) {
            Instant resolvedAt = recent.get(0).getResolvedAt();
            Instant pauseUntil = (resolvedAt == null ? now : resolvedAt)
                    .plus(Duration.ofDays(NEGATIVE_FEEDBACK_PAUSE_DAYS));
            if (pauseUntil.isAfter(now)) {
                return Decision.drop("负反馈暂停");
            }
        }
        return Decision.allow();
    }

    /** 各信号的冷却时长，返回 null 表示只靠 dedupe_key 幂等（每单一次类）。 */
    private Duration cooldownOf(String type) {
        return switch (type) {
            case "CART_IDLE" -> Duration.ofDays(7);
            case "PRICE_DROP" -> Duration.ofDays(30);
            default -> null;
        };
    }

    private boolean enabled(String userId, String type) {
        ProactivePrefEntity pref = prefMapper.selectByUserIdAndType(userId, type);
        return pref == null || pref.getEnabled() != null && pref.getEnabled() == 1;
    }

    private boolean isQuietHours(Instant now) {
        LocalTime time = ZonedDateTime.ofInstant(now, ZONE).toLocalTime();
        return !time.isBefore(QUIET_START) || time.isBefore(QUIET_END);
    }

    /** 免打扰结束时刻：当天 08:00（已过则次日 08:00）。 */
    private Instant nextQuietEnd(Instant now) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(now, ZONE);
        ZonedDateTime end = zdt.toLocalDate().atTime(QUIET_END).atZone(ZONE);
        if (!end.isAfter(zdt)) {
            end = end.plusDays(1);
        }
        return end.toInstant();
    }

    private Instant startOfDay(Instant now) {
        return ZonedDateTime.ofInstant(now, ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
    }

    /** 从最近一条往回数连续 DISMISSED 的条数。 */
    private long dismissStreak(List<ProactiveEventEntity> recentFirst) {
        long streak = 0;
        for (ProactiveEventEntity event : recentFirst) {
            if ("DISMISSED".equals(event.getStatus())) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }
}
