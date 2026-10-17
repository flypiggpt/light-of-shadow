package org.yan.lightofshadowbackend.auth.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 真实 SMTP 发邮件。需要 spring.mail.* 配置齐全，否则 bean 创建失败。
 * 与 DevCodeSender 二选一：本类存在时优先（@Primary）。
 */
@Primary
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class EmailCodeSender implements CodeSender {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeSender.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String appName;

    public EmailCodeSender(JavaMailSender mailSender,
                           @Value("${spring.mail.username:}") String from,
                           @Value("${app.name:Light of Shadow}") String appName) {
        this.mailSender = mailSender;
        this.from = from;
        this.appName = appName;
    }

    @Override
    public void send(String target, String scene, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(target);
        message.setFrom(from);
        message.setSubject("【" + appName + "】验证码");
        message.setText("""
                你的验证码是：%s
                5 分钟内有效，请勿转发给他人。
                若非本人操作，请忽略此邮件。
                """.formatted(code));
        mailSender.send(message);
        log.info("[MAIL] code sent scene={} target={}", scene, target);
    }
}
