package org.yan.lightofshadowbackend.auth.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认发码实现：不发真实邮件，验证码打日志。
 * 配置 spring.mail.host 后 EmailCodeSender 以 @Primary 覆盖本实现。
 */
@Component
public class DevCodeSender implements CodeSender {

    private static final Logger log = LoggerFactory.getLogger(DevCodeSender.class);

    @Override
    public void send(String target, String scene, String code) {
        log.info("[CODE] verification code scene={} target={} code={}", scene, target, code);
    }
}
