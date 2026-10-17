package org.yan.lightofshadowbackend.auth.code;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yan.lightofshadowbackend.common.ErrorCode;
import org.yan.lightofshadowbackend.config.VerificationCodeProperties;
import org.yan.lightofshadowbackend.exception.ApiException;

/**
 * 验证码：生成 / 校验 / 频控。dev 阶段用内存存储，后续可换 Redis 实现（接口不变）。
 */
@Service
public class VerificationCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Scene {
        LOGIN("login"),
        REGISTER("register"),
        RESET("reset");

        private final String value;

        Scene(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Scene from(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "scene不能为空");
            }
            for (Scene scene : values()) {
                if (scene.value.equalsIgnoreCase(raw.trim())) {
                    return scene;
                }
            }
            throw new ApiException(ErrorCode.BAD_REQUEST, "scene必须是 login/register/reset");
        }
    }

    public record SendResult(String code, long expiresIn, long resendAfter) {
    }

    private record CodeEntry(String code, Instant expireAt, int attempts) {
    }

    private final CodeSender codeSender;
    private final VerificationCodeProperties properties;
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSendAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> dailyCounts = new ConcurrentHashMap<>();
    private final Map<String, Instant> dailyResetAt = new ConcurrentHashMap<>();

    public VerificationCodeService(CodeSender codeSender, VerificationCodeProperties properties) {
        this.codeSender = codeSender;
        this.properties = properties;
    }

    /** 生成并发送验证码；返回 dev 回显用的明文码（prod 下调用方应忽略）。 */
    public SendResult send(String target, String scene) {
        String normalized = normalizeTarget(target);
        Scene sceneEnum = Scene.from(scene);
        String key = key(normalized, sceneEnum);

        Instant now = Instant.now();
        Instant last = lastSendAt.get(key);
        if (last != null) {
            Duration since = Duration.between(last, now);
            if (since.compareTo(properties.resendInterval()) < 0) {
                long wait = properties.resendInterval().minus(since).toSeconds();
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, "发送过于频繁，请" + wait + "秒后再试");
            }
        }

        String dayKey = normalized + "|" + now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant dayReset = dailyResetAt.get(dayKey);
        if (dayReset != null && dayReset.isBefore(now)) {
            dailyCounts.remove(dayKey);
            dailyResetAt.remove(dayKey);
        }
        int sentToday = dailyCounts.getOrDefault(dayKey, 0);
        if (sentToday >= properties.dailyLimit()) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, "今日发送次数已达上限");
        }

        String code = generateCode();
        codes.put(key, new CodeEntry(code, now.plus(properties.ttl()), 0));
        lastSendAt.put(key, now);
        dailyCounts.put(dayKey, sentToday + 1);
        dailyResetAt.putIfAbsent(dayKey, now.plus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.DAYS));

        codeSender.send(normalized, sceneEnum.value(), code);
        log.debug("code issued scene={} target={} ttl={}s", sceneEnum.value(), mask(normalized), properties.ttl().toSeconds());

        long resendAfter = properties.resendInterval().toSeconds();
        return new SendResult(code, properties.ttl().toSeconds(), resendAfter);
    }

    /** 校验并消费验证码；通过后该码作废。 */
    public void verifyAndConsume(String target, String scene, String code) {
        String normalized = normalizeTarget(target);
        Scene sceneEnum = Scene.from(scene);
        String key = key(normalized, sceneEnum);

        CodeEntry entry = codes.get(key);
        if (entry == null) {
            throw new ApiException(ErrorCode.CODE_INVALID, "验证码无效或已过期");
        }
        if (entry.expireAt().isBefore(Instant.now())) {
            codes.remove(key);
            throw new ApiException(ErrorCode.CODE_INVALID, "验证码无效或已过期");
        }
        if (entry.attempts() >= properties.maxAttempts()) {
            codes.remove(key);
            throw new ApiException(ErrorCode.CODE_INVALID, "错误次数过多，请重新获取验证码");
        }
        if (code == null || !entry.code().equals(code.trim())) {
            codes.put(key, new CodeEntry(entry.code(), entry.expireAt(), entry.attempts() + 1));
            int left = properties.maxAttempts() - entry.attempts() - 1;
            throw new ApiException(ErrorCode.CODE_INVALID, left > 0 ? "验证码错误，还可尝试" + left + "次" : "验证码错误，请重新获取");
        }
        codes.remove(key);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, properties.length());
        int value = RANDOM.nextInt(bound);
        return String.format("%0" + properties.length() + "d", value);
    }

    private String normalizeTarget(String target) {
        if (target == null || target.trim().isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
        String trimmed = target.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.length() > 128) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "邮箱长度不能超过128");
        }
        // 宽松邮箱格式：本地部分@域名.后缀
        if (!trimmed.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        return trimmed;
    }

    private String key(String target, Scene scene) {
        return scene.value() + ":" + target;
    }

    private String mask(String target) {
        int at = target.indexOf('@');
        if (at <= 2) {
            return "***" + target.substring(at);
        }
        return target.substring(0, 2) + "***" + target.substring(at);
    }
}
