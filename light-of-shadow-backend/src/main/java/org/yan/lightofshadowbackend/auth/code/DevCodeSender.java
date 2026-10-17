package org.yan.lightofshadowbackend.auth.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认发码实现：不发真实邮件，验证码打日志。
 * 未配置 SMTP / 未启用 EmailCodeSender 时始终可用，保证服务能启动。
 * 后续加入 EmailCodeSender 并标记 @Primary 后，自动改走邮件。
 */
@Component
public class DevCodeSender implements CodeSender {

    private static final Logger log = LoggerFactory.getLogger(DevCodeSender.class);

    @Override
    public void send(String target, String scene, String code) {
        log.info("[CODE] verification code scene={} target={} code={}", scene, target, code);
    }
}
