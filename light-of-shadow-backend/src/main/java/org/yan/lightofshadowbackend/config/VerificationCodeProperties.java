package org.yan.lightofshadowbackend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth.code")
public record VerificationCodeProperties(
        Duration ttl,
        Duration resendInterval,
        int dailyLimit,
        int maxAttempts,
        int length
) {
    public VerificationCodeProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(5);
        }
        if (resendInterval == null || resendInterval.isNegative()) {
            resendInterval = Duration.ofSeconds(60);
        }
        if (dailyLimit <= 0) {
            dailyLimit = 10;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (length < 4 || length > 8) {
            length = 6;
        }
    }
}
