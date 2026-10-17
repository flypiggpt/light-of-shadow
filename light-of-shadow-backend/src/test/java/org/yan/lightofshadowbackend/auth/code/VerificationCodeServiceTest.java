package org.yan.lightofshadowbackend.auth.code;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yan.lightofshadowbackend.config.VerificationCodeProperties;
import org.yan.lightofshadowbackend.exception.ApiException;

class VerificationCodeServiceTest {

    private final List<String> sent = new ArrayList<>();
    private VerificationCodeService service;

    @BeforeEach
    void setUp() {
        sent.clear();
        service = new VerificationCodeService(
                (target, scene, code) -> sent.add(scene + ":" + target + ":" + code),
                new VerificationCodeProperties(Duration.ofMinutes(5), Duration.ofSeconds(60), 10, 5, 6));
    }

    @Test
    void sendAndVerifySuccess() {
        VerificationCodeService.SendResult result = service.send("user@example.com", "reset");
        assertNotNull(result.code());
        assertEquals(6, result.code().length());
        assertEquals(1, sent.size());

        assertDoesNotThrow(() -> service.verifyAndConsume("user@example.com", "reset", result.code()));
        // 一次性：再用同一码应失败
        assertThrows(ApiException.class,
                () -> service.verifyAndConsume("user@example.com", "reset", result.code()));
    }

    @Test
    void wrongCodeCountsAttempts() {
        VerificationCodeService.SendResult result = service.send("user@example.com", "reset");
        for (int i = 0; i < 5; i++) {
            assertThrows(ApiException.class,
                    () -> service.verifyAndConsume("user@example.com", "reset", "000000"));
        }
        assertThrows(ApiException.class,
                () -> service.verifyAndConsume("user@example.com", "reset", result.code()));
    }

    @Test
    void resendIntervalEnforced() {
        service.send("user@example.com", "reset");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.send("user@example.com", "reset"));
        assertEquals(429, ex.getErrorCode().code());
    }

    @Test
    void rejectsBadEmail() {
        assertThrows(ApiException.class, () -> service.send("not-an-email", "reset"));
    }

    @Test
    void rejectsUnknownScene() {
        assertThrows(ApiException.class, () -> service.send("user@example.com", "nope"));
    }
}
