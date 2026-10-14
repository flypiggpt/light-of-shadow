package org.yan.lightofshadowbackend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(Jwt jwt, Duration accessTokenTtl, Duration refreshTokenTtl) {

    public record Jwt(String issuer, String secret) {
    }
}

